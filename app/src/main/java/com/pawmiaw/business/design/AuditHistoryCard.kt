package com.pawmiaw.business

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import com.pawmiaw.business.inbox.InboxOlfatoWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.text.NumberFormat

//
//  AuditHistoryCard.kt (iOS AuditHistoryCard.swift)
//
//  Every change to one thing and who made it (`GET /audit-events`), for any
//  entity (web components/app/audit-history-panel.tsx, olfato/olfato-access.tsx):
//  20 at a time with "Show more entries", the count from the server's total,
//  each entry opening to its field-by-field changes (Spanish reads the
//  server's action and field codes), the signature kept with a closing, and
//  optional chips that narrow it to one part of a page. On a pet's history,
//  who asked Olfato AI about the pet joins the changes by time (one line per
//  question, never its text); the same Olfato line is the team's log.
//
//  Used by Clients (a client, a pet's chart, an invitation), Records (a
//  record), Team (a staff member, Availability, Settings) and Team's Olfato
//  AI log and member card (`OlfatoAccessLine`).
//

// MARK: - What the server sends

@Serializable
data class AuditChange(
    val field: String? = null,
    val label: String? = null,
    val before: JSONValue? = null,
    val after: JSONValue? = null,
    val type: String? = null,
    /** Its values are not shown (a PIN, a secret): only that it changed. */
    val sensitive: Boolean? = null,
)

@Serializable
data class AuditEntry(
    val id: String,
    val occurredAt: String? = null,
    val action: String? = null,
    val actionLabel: String? = null,
    val outcome: String? = null,
    val summary: String? = null,
    val actorDisplayName: String? = null,
    val source: String? = null,
    val changes: List<AuditChange>? = null,
    val metadata: Map<String, JSONValue>? = null,
)

/**
 * A part of a page a history's chips narrow it to (Settings: profile, contact, services…), by the
 * server's action names that change it.
 */
data class AuditArea(val key: String, val label: String, val actions: List<String>)

/** A tool or an area of an Olfato AI question as people read it ("Finanzas del negocio"). */
@Serializable
data class OlfatoAreaRef(val name: String? = null, val area: String? = null, val label: InboxOlfatoWords? = null)

/** One question to Olfato AI in a log. Never its text. */
@Serializable
data class OlfatoAccessEntry(
    val id: String,
    val at: String? = null,
    /** The day in the workspace's calendar (YYYY-MM-DD). */
    val day: String? = null,
    val member: Member? = null,
    val way: String? = null,
    val chatId: String? = null,
    val tools: List<OlfatoAreaRef>? = null,
    val refused: List<OlfatoAreaRef>? = null,
    val pets: List<Pet>? = null,
) {
    @Serializable
    data class Member(
        val id: String? = null,
        val name: String? = null,
        val isYou: Boolean? = null,
        /** No longer on the team: the line stays, by name. */
        val left: Boolean? = null,
    )

    @Serializable
    data class Pet(val id: String, val name: String? = null)
}

/** How an Olfato AI log line says things (web lib/olfato-access.ts). */
object OlfatoAccessWords {
    /** Who asked: "You", their name, or their name and that they left. */
    fun personName(member: OlfatoAccessEntry.Member?): String {
        if (member?.isYou == true) return L("You")
        val name = member?.name?.trim().nonEmpty ?: L("Someone")
        return if (member?.left == true) LF("%@ (left the team)", name) else name
    }

    /** The kinds of information a line read, each once (two tools of one area are one kind). */
    fun kinds(refs: List<OlfatoAreaRef>?): List<OlfatoAreaRef> {
        val seen = HashSet<String>()
        val out = ArrayList<OlfatoAreaRef>()
        for (ref in refs ?: emptyList()) {
            val key = ref.area ?: ref.name ?: ""
            if (seen.add(key)) out.add(ref)
        }
        return out
    }

    fun words(ref: OlfatoAreaRef): String = ref.label?.text.nonEmpty ?: ref.area ?: ref.name ?: ""
}

// MARK: - The card

/** `AuditHistoryCard.pageSize` and the words (the server names actions and fields in English; Spanish reads their codes). */
object AuditHistoryCard {
    const val pageSize = 20

    fun sourceText(source: String?): String? {
        val value = source.nonEmpty ?: return null
        return when (value) {
            "web" -> L("Web")
            "api" -> L("API")
            else -> value.split("_").joinToString(" ") { part -> part.take(1).uppercase() + part.drop(1) }
        }
    }

    /** English reads the server's label; Spanish builds "Cliente · perfil: actualizado" from the action code. */
    fun actionText(item: AuditEntry): String {
        val action = item.action.nonEmpty
        if (LocalizationManager.currentLanguage != AppLanguage.spanish || action == null) return item.actionLabel ?: item.action ?: ""
        val parts = action.split(".").toMutableList()
        val entity = parts.firstOrNull() ?: return item.actionLabel ?: action
        parts.removeAt(0)
        val verb = parts.removeLastOrNull()
        val subject = (listOf(esEntities[entity] ?: entity.replace("_", " ")) + parts.map { esParts[it] ?: it.replace("_", " ") }).joinToString(" · ")
        if (verb == null) return subject
        return "$subject: ${esVerbs[verb] ?: verb.replace("_", " ")}"
    }

    fun fieldText(change: AuditChange): String {
        val field = change.field
        if (LocalizationManager.currentLanguage == AppLanguage.spanish && field != null) esFields[field]?.let { return it }
        return change.label.nonEmpty ?: change.field ?: "—"
    }

    fun valueText(value: JSONValue?): String {
        if (value == null || value is JsonNull) return L("Empty")
        if (value is JsonPrimitive) {
            if (value.isString) return if (value.content.isEmpty()) L("Empty") else value.content
            if (value.booleanOrNull != null) return if (value.boolean) L("Yes") else L("No")
            val number = value.doubleOrNull ?: return value.content
            return NumberFormat.getNumberInstance(appLocale).apply { maximumFractionDigits = 6 }.format(number)
        }
        // Arrays and objects: the JSON as it came.
        return value.toString()
    }

    // The Spanish tables (audit-history-panel.tsx).
    private val esEntities: Map<String, String> = mapOf(
        "provider_appointment" to "Cita", "appointment" to "Cita", "provider_client" to "Cliente", "provider_client_invitation" to "Invitación",
        "provider_invoice" to "Cuenta", "provider_pet" to "Mascota", "pet" to "Mascota", "provider_record" to "Registro", "provider_store" to "Tienda",
        "provider_catalog" to "Catálogo", "provider_inventory" to "Inventario", "provider_stay" to "Estancia", "provider_follow_up" to "Seguimiento",
        "household" to "Hogar", "routine" to "Rutina", "conversation" to "Conversación", "provider_room" to "Espacio", "client" to "Cliente",
    )

    private val esParts: Map<String, String> = mapOf(
        "confirmation" to "confirmación", "attachment" to "archivo", "profile" to "perfil", "weight" to "peso", "photo" to "foto", "record" to "registro",
        "settings" to "ajustes", "register" to "caja", "stock" to "inventario", "sale" to "venta", "item" to "artículo", "invite" to "invitación",
        "message" to "mensaje", "cabinet" to "botiquín", "document" to "documento", "routine_log" to "bitácora", "checkin" to "registro",
        "report" to "reporte", "note" to "nota", "death" to "fallecimiento",
    )

    private val esVerbs: Map<String, String> = mapOf(
        "created" to "creado", "updated" to "actualizado", "deleted" to "eliminado", "accessed" to "consultado", "added" to "agregado", "removed" to "quitado",
        "uploaded" to "subido", "attachment_uploaded" to "archivo subido", "attachment_deleted" to "archivo eliminado", "accepted" to "aceptada",
        "expired" to "vencida", "resent" to "reenviada", "revoked" to "revocada", "requested" to "solicitada", "client_confirmed" to "confirmada por el cliente",
        "link_opened" to "enlace abierto", "cancellation_requested" to "cancelación solicitada", "reschedule_requested" to "cambio de hora solicitado",
        "sync_completed" to "sincronizado", "update_rejected" to "cambio rechazado", "completed" to "completado", "downloaded" to "descargado",
        "opened" to "abierta", "closed" to "cerrada", "cash_moved" to "movimiento de efectivo", "received" to "recibido", "returned" to "devuelta",
        "signed" to "firmado", "amended" to "corregido", "checked_in" to "llegada registrada", "started" to "empezada", "finished" to "terminada",
        "cancelled" to "cancelada", "no_show" to "no llegó", "rebooked" to "reagendada", "reopened" to "reabierta", "moved" to "movida",
        "recorded" to "registrado", "archived" to "en archivo", "restored" to "de vuelta en las listas", "moved_out" to "pasó a otro cliente",
        "moved_in" to "llegó de otro cliente", "discarded" to "descartado", "reminded" to "recordado",
    )

    private val esFields: Map<String, String> = mapOf(
        "status" to "Estado", "startsAt" to "Inicio", "endsAt" to "Fin", "room" to "Espacio", "roomId" to "Espacio", "notes" to "Notas",
        "serviceLabel" to "Servicio", "serviceId" to "Servicio", "serviceCategory" to "Tipo de servicio", "assignedOrganizationMemberId" to "Asignado a",
        "confirmationStatus" to "Confirmación", "title" to "Título", "summary" to "Resumen", "details" to "Detalles", "internalNote" to "Nota interna",
        "eventAt" to "Fecha", "followUpAt" to "Seguimiento", "payload" to "Datos", "recordType" to "Tipo de registro", "ownerName" to "Nombre",
        "ownerLastName" to "Apellido", "ownerEmail" to "Correo", "ownerPhone" to "Teléfono", "petName" to "Nombre de la mascota", "petBreed" to "Raza",
        "petSpecies" to "Especie", "weight" to "Peso", "sex" to "Sexo", "birthDate" to "Nacimiento", "microchipNumber" to "Microchip", "photoUrl" to "Foto",
        "totalAmount" to "Total", "amountPaid" to "Pagado", "balanceDueAmount" to "Saldo", "dueAt" to "Vence", "name" to "Nombre", "capacity" to "Capacidad",
        "doubleBooked" to "Doble reserva", "hoursOverride" to "Fuera del horario", "arrivalNote" to "Nota de llegada", "cancelReason" to "Motivo de cancelación",
    )
}

/** A line of the history: a change, or (on a pet's) one Olfato AI question (iOS's private `Line`). */
private sealed class AuditHistoryCardLine {
    data class audit(val item: AuditEntry) : AuditHistoryCardLine()
    data class olfato(val entry: OlfatoAccessEntry) : AuditHistoryCardLine()

    val id: String
        get() = when (this) {
            is audit -> "a:${item.id}"
            is olfato -> "o:${entry.id}"
        }

    val at: String
        get() = when (this) {
            is audit -> item.occurredAt ?: ""
            is olfato -> entry.at ?: ""
        }
}

/**
 * The history card (web `AuditHistoryPanel`): the header as its own button, then the entries
 * between hairlines, edge to edge.
 *
 * [openArea]: the Swift hands back the part's page for a `NavigationLink`; the Android stack
 * pushes only `ProviderRoute`s, so the caller opens the part with its key itself.
 * [olfatoPet]: the Swift `(id:, name:)`, as `id to name`.
 */
@Composable
fun AuditHistoryCard(
    entityType: String,
    entityId: String,
    title: String = L("Audit history"),
    description: String = L("A compact record of changes, staff actions, and system updates for this item."),
    /** Folded until asked for (the web's panel); a page of its own shows it open with no fold. */
    collapsible: Boolean = true,
    startsOpen: Boolean = false,
    emptyText: String = L("No audit history for this record yet."),
    /** Chips that narrow it to one part; each entry links to its part with `openArea`. */
    areas: List<AuditArea> = emptyList(),
    modifier: Modifier = Modifier,
    openArea: ((String) -> Unit)? = null,
    /** A pet's history: who asked Olfato AI about it joins the changes by time. */
    olfatoPet: Pair<String, String>? = null,
) {
    var open by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var auditTotal by remember { mutableStateOf(0) }
    var auditMore by remember { mutableStateOf(false) }
    var olfato by remember { mutableStateOf<List<OlfatoAccessEntry>>(emptyList()) }
    var olfatoTotal by remember { mutableStateOf(0) }
    var olfatoMore by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var area by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val currentAreas by rememberUpdatedState(areas)
    val currentPet by rememberUpdatedState(olfatoPet)
    val isOpen = !collapsible || open

    suspend fun load(reset: Boolean) {
        if (loading && !reset) return
        loading = true
        try {
            val actions = currentAreas.firstOrNull { it.key == area }?.actions ?: emptyList()
            if (reset || auditMore) {
                try {
                    val page: Page<AuditEntry> = API.get(
                        "/audit-events",
                        mapOf(
                            "entityType" to entityType, "entityId" to entityId, "limit" to "${AuditHistoryCard.pageSize}",
                            "offset" to "${if (reset) 0 else audit.size}",
                            "actions" to (if (actions.isEmpty()) null else actions.sorted().joinToString(",")),
                        ),
                    )
                    if (reset) audit = page.items else {
                        val known = audit.map { it.id }.toSet()
                        audit = audit + page.items.filter { it.id !in known }
                    }
                    auditTotal = page.total ?: audit.size
                    auditMore = page.hasMore ?: (audit.size < auditTotal)
                    failed = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    auditMore = false
                    if (audit.isNotEmpty()) Toast.error(e)
                }
            }
            // The Olfato AI lines are an extra: when they can't be read, the history is the changes alone.
            val pet = currentPet
            if (pet != null && (reset || olfatoMore)) {
                val page: Page<OlfatoAccessEntry>? = try {
                    API.get("/provider-olfato/access/pets/${pet.first}", mapOf("limit" to "${AuditHistoryCard.pageSize}", "offset" to "${if (reset) 0 else olfato.size}"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (page != null) {
                    if (reset) olfato = page.items else {
                        val known = olfato.map { it.id }.toSet()
                        olfato = olfato + page.items.filter { it.id !in known }
                    }
                    olfatoTotal = page.total ?: olfato.size
                    olfatoMore = page.hasMore ?: (olfato.size < olfatoTotal)
                } else {
                    olfatoMore = false
                }
            }
        } finally {
            loading = false
            loaded = true
        }
    }

    fun choose(key: String?) {
        area = key
        scope.launch { load(reset = true) }
    }

    LaunchedEffect(Unit) {
        if ((!collapsible || startsOpen) && !loaded) {
            open = true
            load(reset = true)
        }
    }
    // `.onReceive(.pawMiawDataChanged)`: an open, loaded history reads again.
    LaunchedEffect(Unit) {
        AppEvents.dataChanged.collect {
            if ((!collapsible || open) && loaded) launch { load(reset = true) }
        }
    }

    /** Both lists by time; a line shows only once nothing still unread can be newer. */
    val lines: List<AuditHistoryCardLine> = run {
        val all = (audit.map { AuditHistoryCardLine.audit(it) } + olfato.map { AuditHistoryCardLine.olfato(it) }).sortedByDescending { it.at }
        val floor = listOfNotNull(if (auditMore) audit.lastOrNull()?.occurredAt else null, if (olfatoMore) olfato.lastOrNull()?.at else null).maxOrNull()
        if (floor == null || olfatoPet == null) all else all.filter { it.at >= floor }
    }

    Column(modifier.listCard()) {
        // MARK: Header
        val total = auditTotal + olfatoTotal
        val chevron by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "chevron")
        val face: @Composable (Modifier) -> Unit = { faceModifier ->
            Row(faceModifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).background(Theme.wash(Theme.aqua, 18.0), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph("history", 16.dp, tint = Theme.blue)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = Theme.heading(18), color = Theme.inkStrong, modifier = Modifier.weight(1f, fill = false))
                        if (loaded && total > 0) Chip(text = "$total", weight = FontWeight.SemiBold)
                    }
                    Text(description, style = Theme.text(14), color = Theme.inkSoft)
                }
                Spacer(Modifier.width(8.dp))
                if (collapsible) {
                    Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 4.dp).rotate(chevron))
                }
            }
        }
        if (collapsible) {
            face(
                Modifier
                    .auditPlainPress {
                        open = !open
                        if (open && !loaded) scope.launch { load(reset = true) }
                    }
                    .semantics { heading() }
            )
        } else {
            face(Modifier)
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        ) {
            Column {
                RowDivider()
                // MARK: Content
                if (areas.isNotEmpty()) {
                    CardToolbar {
                        FlowLayout(spacing = 6.dp, modifier = Modifier.semantics { contentDescription = L("Show changes to") }) {
                            for (item in areas) {
                                FilterChip(title = item.label, isSelected = area == item.key) { choose(if (area == item.key) null else item.key) }
                            }
                        }
                    }
                }
                when {
                    !loaded -> LoadingState(modifier = Modifier.heightIn(min = 120.dp))
                    failed && audit.isEmpty() -> {
                        val shape = RoundedCornerShape(Theme.chipRadius)
                        Text(
                            L("Audit history is unavailable right now."),
                            style = Theme.text(14),
                            color = Theme.inkSoft,
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                                .background(Theme.surfaceMuted, shape)
                                .border(1.dp, Theme.borderSoft, shape)
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                    lines.isEmpty() -> {
                        if (area != null) {
                            EmptyState(
                                systemImage = "shield-check",
                                title = L("No changes to this part yet."),
                                message = L("Choose another part, or see all of them."),
                                actionTitle = L("See all changes"),
                                action = { choose(null) },
                            )
                        } else {
                            EmptyState(systemImage = "shield-check", title = emptyText)
                        }
                    }
                    else -> {
                        Column {
                            lines.forEachIndexed { index, line ->
                                if (index > 0) RowDivider()
                                when (line) {
                                    is AuditHistoryCardLine.audit -> AuditEntryRow(
                                        item = line.item,
                                        isOpen = line.item.id in expanded,
                                        areas = areas,
                                        openArea = openArea,
                                        onToggle = {
                                            val id = line.item.id
                                            expanded = if (id in expanded) expanded - id else expanded + id
                                        },
                                    )
                                    is AuditHistoryCardLine.olfato -> OlfatoAccessLine(
                                        entry = line.entry,
                                        aboutPet = olfatoPet?.second ?: "",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        if (auditMore || olfatoMore) {
                            RowDivider()
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                PMButton(
                                    if (loading) L("Loading...") else L("Show more entries"),
                                    systemImage = "chevron-down",
                                    variant = ButtonVariant.ghost,
                                    size = ButtonSize.small,
                                    isWorking = loading,
                                ) { scope.launch { load(reset = false) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One change (web `AuditEntry`): a 24 aqua disc with the chevron, the summary semibold (a
 * pink outcome tag when it didn't succeed), when · who · from where; open, its fields in a
 * bordered box: before and after under uppercase heads.
 */
@Composable
private fun AuditEntryRow(
    item: AuditEntry,
    isOpen: Boolean,
    areas: List<AuditArea>,
    openArea: ((String) -> Unit)?,
    onToggle: () -> Unit,
) {
    val changes = item.changes ?: emptyList()
    val chevron by animateFloatAsState(if (isOpen) 90f else 0f, tween(150), label = "entry")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .auditPlainPress(onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(24.dp)
                    .background(Theme.wash(Theme.aqua, 22.0), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Glyph("chevron-right", 14.dp, tint = Theme.blue, modifier = Modifier.rotate(chevron))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowLayout(spacing = 8.dp) {
                    Text(item.summary.nonEmpty ?: AuditHistoryCard.actionText(item), style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong)
                    val outcome = item.outcome
                    if (outcome != null && outcome != "success") {
                        TagChip(text = outcome, tone = Theme.pink, size = TagChip.Size.sm)
                    }
                }
                Text(
                    listOfNotNull(DateText.dayTime(item.occurredAt), item.actorDisplayName.nonEmpty ?: L("System"), AuditHistoryCard.sourceText(item.source))
                        .mapNotNull { it.nonEmpty }
                        .joinToString(" · "),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                )
                if (!isOpen && changes.isNotEmpty()) {
                    Text(LP(changes.size, "%d field changed", "%d fields changed"), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }

        val action = item.action
        val part = if (action != null) areas.firstOrNull { action in it.actions } else null
        if (part != null && openArea != null) {
            Box(Modifier.padding(start = 40.dp, bottom = 8.dp)) {
                PMButton(LF("Open %@", part.label), variant = ButtonVariant.link, size = ButtonSize.slim) { openArea(part.key) }
            }
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(tween(150)) + expandVertically(tween(150)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            Column(
                Modifier
                    .padding(start = 52.dp, end = 16.dp, bottom = 16.dp)
                    .fillMaxWidth()
                    .background(Theme.surface, RoundedCornerShape(Theme.boxRadius))
                    .listCard(ListCardStyle.box),
            ) {
                Text(
                    AuditHistoryCard.actionText(item),
                    style = Theme.text(12, FontWeight.SemiBold),
                    color = Theme.inkSoft,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                RowDivider()
                if (changes.isEmpty()) {
                    Text(L("No field-level changes were captured for this action."), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(12.dp))
                }
                changes.forEachIndexed { index, change ->
                    if (index > 0) RowDivider()
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(AuditHistoryCard.fieldText(change), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                        if (change.sensitive != true) {
                            AuditValueLine(L("Before"), AuditHistoryCard.valueText(change.before), strong = false)
                            AuditValueLine(L("After"), AuditHistoryCard.valueText(change.after), strong = true)
                        }
                    }
                }
                // What was signed to confirm it (a death, an archived client): the signature given then.
                val signatureId = item.metadata?.get("signatureId").string.nonEmpty
                if (signatureId != null) {
                    RowDivider()
                    AuditSignatureFigure(signatureId = signatureId, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

/** "BEFORE value" / "AFTER value": the head 10.9 bold uppercase tracked, the value 14. */
@Composable
private fun AuditValueLine(head: String, value: String, strong: Boolean) {
    val soft = Theme.inkSoft
    val ink = if (strong) Theme.inkStrong else Theme.inkSoft
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = BrandFonts.text, fontWeight = FontWeight.Bold, fontSize = 10.9.sp, letterSpacing = 1.3.sp, color = soft)) {
                append(head.uppercase() + " ")
            }
            withStyle(SpanStyle(color = ink)) { append(value) }
        },
        style = Theme.text(14),
    )
}

/** The signature kept with an archive or a death (an audit entry's `signatureId`; iOS's private `Kept`). */
@Serializable
private data class AuditSignatureFigureKept(
    val imageDataUrl: String? = null,
    val signerName: String? = null,
    val byName: String? = null,
    val signedAt: String? = null,
    val createdAt: String? = null,
)

/** The signature kept with an archive or a death (an audit entry's `signatureId`). */
@Composable
private fun AuditSignatureFigure(signatureId: String, modifier: Modifier = Modifier) {
    var kept by remember { mutableStateOf<AuditSignatureFigureKept?>(null) }
    LaunchedEffect(signatureId) {
        kept = try {
            API.get<AuditSignatureFigureKept>("/provider-closing-signatures/$signatureId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val signature = kept
        val image = remember(signature?.imageDataUrl) { SignatureRules.image(signature?.imageDataUrl) }
        if (signature != null && image != null) {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(Theme.chipRadius))
                    .padding(6.dp)
                    .heightIn(max = 80.dp),
            )
            val who = signature.signerName.nonEmpty ?: signature.byName.nonEmpty
            val day = DateText.date(signature.signedAt ?: signature.createdAt)
            Text(if (who != null) LF("Signed by %1\$@ on %2\$@", who, day) else LF("Signed on %@", day), style = Theme.text(12), color = Theme.inkSoft)
        }
    }
}

// MARK: - One Olfato AI question

/** Draws the OlfatoFace SVG whether or not the app's image loader knows SVG. */
private val olfatoFaceLoader: ImageLoader by lazy {
    ImageLoader.Builder(AppContext.context).components { add(SvgDecoder.Factory()) }.build()
}

/**
 * One question: who (unless the list is one person's), the way in, when and what it read. On a
 * pet's history (`aboutPet`) it reads "Ana asked Olfato AI about Luna", with the kinds on the date line.
 */
@Composable
fun OlfatoAccessLine(
    entry: OlfatoAccessEntry,
    showPerson: Boolean = true,
    /** The pet whose history this line is in. */
    aboutPet: String? = null,
    modifier: Modifier = Modifier,
    /** Narrow the list to this pet (the team's log). */
    onPickPet: ((OlfatoAccessEntry.Pet) -> Unit)? = null,
    /** Narrow the list to this person (the team's log). */
    onPickMember: ((String) -> Unit)? = null,
) {
    Row(modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        val face = rememberAsyncImagePainter("file:///android_asset/img/OlfatoFace.svg", imageLoader = olfatoFaceLoader)
        Box(
            Modifier
                .size(32.dp)
                .background(Theme.wash(Theme.aqua, 35.0), CircleShape)
                .padding(4.dp)
                .clearAndSetSemantics {},
        ) {
            Image(face, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(24.dp))
        }
        Box(Modifier.weight(1f)) {
            if (aboutPet != null) OlfatoPetLine(entry, aboutPet) else OlfatoLogLine(entry, showPerson, onPickPet, onPickMember)
        }
    }
}

@Composable
private fun OlfatoPetLine(entry: OlfatoAccessEntry, petName: String) {
    val pet = petName.trim().nonEmpty ?: L("a pet")
    val refused = OlfatoAccessWords.kinds(entry.refused).map { OlfatoAccessWords.words(it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (entry.member?.isYou == true) LF("You asked Olfato AI about %@", pet) else LF("%1\$@ asked Olfato AI about %2\$@", OlfatoAccessWords.personName(entry.member), pet),
            style = Theme.text(16, FontWeight.SemiBold),
            color = Theme.inkStrong,
        )
        Text(
            (listOf(DateText.dayTime(entry.at)) + OlfatoAccessWords.kinds(entry.tools).map { OlfatoAccessWords.words(it) }).filter { it.isNotEmpty() }.joinToString(" · "),
            style = Theme.text(12),
            color = Theme.inkSoft,
        )
        if (refused.isNotEmpty()) HueChip(text = LF("Not open to them: %@", refused.joinToString(", ")), hue = Hue.butter)
    }
}

@Composable
private fun OlfatoLogLine(
    entry: OlfatoAccessEntry,
    showPerson: Boolean,
    onPickPet: ((OlfatoAccessEntry.Pet) -> Unit)?,
    onPickMember: ((String) -> Unit)?,
) {
    val kinds = OlfatoAccessWords.kinds(entry.tools)
    val refused = OlfatoAccessWords.kinds(entry.refused)
    val pets = entry.pets ?: emptyList()
    val way = if (entry.way == "pet") L("From a pet") else L("About the business")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showPerson) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val person = OlfatoAccessWords.personName(entry.member)
                val memberId = entry.member?.id
                if (onPickMember != null && memberId != null) {
                    val description = LF("Show only %@", person)
                    Text(
                        person,
                        style = Theme.text(14, FontWeight.Bold),
                        color = Theme.inkStrong,
                        modifier = Modifier
                            .auditPlainPress { onPickMember(memberId) }
                            .semantics { contentDescription = description },
                    )
                } else {
                    Text(person, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                }
                Text("· $way", style = Theme.text(14), color = Theme.inkSoft)
            }
        } else {
            Text(way, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        }
        Text(DateText.dayTime(entry.at), style = Theme.text(12), color = Theme.inkSoft)
        if (pets.isNotEmpty() || kinds.isNotEmpty() || refused.isNotEmpty()) {
            FlowLayout(spacing = 6.dp) {
                for (pet in pets) {
                    val name = pet.name?.trim().nonEmpty ?: L("a pet")
                    if (onPickPet != null) {
                        val description = LF("Pet: %@", name)
                        Box(Modifier.auditPlainPress { onPickPet(pet) }.clearAndSetSemantics { contentDescription = description }) {
                            HueChip(text = name, hue = Hue.sky, systemImage = "pawprint.fill")
                        }
                    } else {
                        HueChip(text = name, hue = Hue.sky, systemImage = "pawprint.fill")
                    }
                }
                for (kind in kinds) HueChip(text = OlfatoAccessWords.words(kind), hue = Hue.grey)
                if (refused.isNotEmpty()) {
                    HueChip(text = LF("Not open to them: %@", refused.joinToString(", ") { OlfatoAccessWords.words(it) }), hue = Hue.butter)
                }
            }
        }
    }
}

/** `.buttonStyle(.plain)` / a plain `Button`: the whole area taps, dimmed while pressed. */
@Composable
private fun Modifier.auditPlainPress(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .alpha(if (pressed) 0.7f else 1f)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
}
