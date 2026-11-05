package com.pawmiaw.business.team

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.UUID

//
//  TeamConsentTemplates.kt (iOS TeamConsentTemplates.swift)
//
//  Settings › Consents (web: components/app/consents/consent-templates-section.tsx
//  and consent-template-editor.tsx; hooks/use-consents.ts; lib/consents.ts):
//  the business's consent templates, active and archived, each with how long
//  a signature lasts and what it is required for. Opening one edits it (with
//  workspace.edit) or reads it: its words with placeholders put in at the
//  cursor and a preview with sample values, which services and stays can't
//  start without it, archive/restore, and every version of its words. New
//  words make a new version; consents already signed keep the words they
//  were signed with. The defaults arrive the first time the list is read.
//

@Serializable
data class TeamConsentTemplate(
    val id: String,
    val defaultKey: String? = null,
    val language: String? = null,
    val title: String? = null,
    val body: String? = null,
    val version: Int? = null,
    val validity: String? = null,
    val validDays: Int? = null,
    val requiredFor: Requirement? = null,
    val needsAmount: Boolean? = null,
    val status: String? = null,
    val updatedAt: String? = null,
) {
    @Serializable
    data class Requirement(
        val serviceIds: List<String>? = null,
        val stayKinds: List<String>? = null,
    )
}

@Serializable
data class TeamConsentVersion(
    val id: String,
    val version: Int? = null,
    val title: String? = null,
    val body: String? = null,
    val language: String? = null,
    val createdAt: String? = null,
    val createdByName: String? = null,
)

@Serializable
private data class TeamConsentTemplateDetail(
    val id: String,
    val language: String? = null,
    val title: String? = null,
    val body: String? = null,
    val version: Int? = null,
    val validity: String? = null,
    val validDays: Int? = null,
    val requiredFor: TeamConsentTemplate.Requirement? = null,
    val status: String? = null,
    val updatedAt: String? = null,
    val signedCount: Int? = null,
    val versions: List<TeamConsentVersion>? = null,
)

@Serializable
private data class TeamConsentLibraryEntry(
    val key: String,
    val language: String? = null,
    val title: String? = null,
    val body: String? = null,
    val validity: String? = null,
    val validDays: Int? = null,
)

/** The consent rules the screens share (web: lib/consents.ts, the server's limits). */
object TeamConsentRules {
    val placeholders = listOf("pet", "client", "amount", "date", "business", "service")
    val stayKinds = listOf("boarding", "daycare", "home_sitting")
    const val titleMax = 160
    const val bodyMax = 12_000
    const val validDaysMax = 3650

    fun placeholderLabel(key: String): String = when (key) {
        "pet" -> L("Pet")
        "client" -> L("Client")
        "amount" -> L("Amount")
        "date" -> L("Date")
        "business" -> L("Business")
        "service" -> L("Service")
        else -> key
    }

    /** A stay kind in words; one this version doesn't know yet reads as it is written. */
    fun stayKindLabel(kind: String): String = when (kind) {
        "boarding" -> L("Boarding (hotel)")
        "daycare" -> L("Daycare")
        "home_sitting" -> L("Home sitting")
        else -> kind.replace("_", " ")
    }

    fun validityLabel(validity: String?, days: Int?): String {
        if (validity != "standing") return L("Each visit or stay")
        if (days == null || days <= 0) return L("Once per pet · until revoked")
        return if (days == 1) L("Once per pet · 1 day") else LF("Once per pet · %d days", days)
    }

    /** A text that names {amount} can't go out until the business says how much. */
    fun needsAmount(title: String, body: String): Boolean = "$title\n$body".contains("{amount}")

    /** Words that stand in for the real ones in the preview. */
    fun samples(language: String, business: String?): Map<String, String> =
        if (language == "es") mapOf(
            "pet" to "Luna", "client" to "María López", "amount" to "$4,500.00 MXN", "date" to "1 de octubre de 2026",
            "business" to (business?.trim().nonEmpty ?: "Tu negocio"), "service" to "Esterilización",
        ) else mapOf(
            "pet" to "Luna", "client" to "Mary Lopez", "amount" to "$450.00 USD", "date" to "October 1, 2026",
            "business" to (business?.trim().nonEmpty ?: "Your business"), "service" to "Spay surgery",
        )

    /** Unknown braces stay as typed; a known placeholder with no value stays visible. */
    fun render(text: String, values: Map<String, String>): String {
        var out = text
        for (key in placeholders) {
            val value = values[key]
            if (!value.isNullOrEmpty()) out = out.replace("{$key}", value)
        }
        return out
    }

    /**
     * `{key}` put where the cursor is (replacing a selection, `start until end`), with a space added
     * when it would otherwise stick to a word or a comma. Returns the new text and where the cursor goes.
     */
    fun insert(key: String, into: String, range: IntRange?): Pair<String, Int> {
        val text = into
        val start = (range?.first ?: text.length).coerceIn(0, text.length)
        val end = (range?.let { it.last + 1 } ?: text.length).coerceIn(start, text.length)
        val before = text.substring(0, start)
        val after = text.substring(end)
        val glueBefore = before.lastOrNull()?.let { !it.isWhitespace() && !"(«\"“'¡¿[".contains(it) } ?: false
        val glueAfter = after.firstOrNull()?.let { it.isLetter() || it.isDigit() } ?: false
        val token = (if (glueBefore) " " else "") + "{$key}" + (if (glueAfter) " " else "")
        return (before + token + after) to (before.length + token.length - (if (glueAfter) 1 else 0))
    }

    fun message(error: Throwable, fallback: String): String = errorText(error, fallback = fallback)
}

// MARK: - The list

@Composable
fun TeamConsentTemplatesSection(model: TeamSettingsModel, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf<List<TeamConsentTemplate>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val tab = remember { mutableStateOf("active") }
    var target by remember { mutableStateOf<TeamConsentTarget?>(null) }
    val canEdit = workspace.can(Permission.workspaceEdit)

    suspend fun load() {
        try {
            templates = API.get("/provider-consent-templates", query = mapOf("includeArchived" to "true"))
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (templates == null) failed = errorText(e, fallback = L("The consents could not be loaded."))
        }
    }

    LaunchedEffect(Unit) { if (templates == null) load() }

    fun requiredLabel(template: TeamConsentTemplate): String? {
        val services = model.catalog?.services ?: emptyList()
        val names = (template.requiredFor?.stayKinds ?: emptyList()).map { TeamConsentRules.stayKindLabel(it) } +
            (template.requiredFor?.serviceIds ?: emptyList()).map { id -> services.firstOrNull { it.id == id }?.label ?: id }
        if (names.isEmpty()) return null
        return if (names.size <= 3) names.joinToString(", ") else LF("%1\$@ and %2\$d more", names.take(3).joinToString(", "), names.size - 3)
    }

    val all = templates ?: emptyList()
    val shown = all.filter { (it.status ?: "active") == tab.value }
    TeamPanel(
        title = L("Consents"),
        subtitle = L("What families sign before a surgery, a sedation, a stay… in the app or here on your device. Each one can be required before a service starts."),
        systemImage = "file-pen-line",
        tint = Theme.lavender,
        look = TeamCardLook.settings,
        readOnlyNote = if (canEdit) null else L("Only someone who edits the workspace can change consents."),
        modifier = modifier,
        action = {
            if (canEdit) {
                PMButton(L("New consent"), systemImage = "file-plus-2", fullWidth = true) { target = TeamConsentTarget(templateId = null) }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = L("Wording must be reviewed by a professional before you rely on it."),
                message = L("The starting texts follow public guidance (NOM-064-ZOO-2000, AVMA) but aren’t legal advice for your business."),
                icon = "scale",
                look = NoticeCard.Look.banner,
            )
            Segmented(
                options = listOf(
                    Segmented.Option("active", L("Active@consents"), icon = "list-checks"),
                    Segmented.Option("archived", L("Archived@consents"), icon = "archive"),
                ),
                selection = tab,
                look = Segmented.Look.solid,
                label = L("Consents"),
            )
            if (templates == null) {
                val error = failed
                if (error != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, textAlign = TextAlign.Center)
                        PMButton(L("Try again"), variant = ButtonVariant.outline) { scope.launch { load() } }
                    }
                } else {
                    TeamProgress(minHeight = 80.dp)
                }
            } else if (shown.isEmpty()) {
                EmptyState(
                    systemImage = if (tab.value == "active") "file-pen-line" else "archive",
                    title = if (tab.value == "active") L("No active consents") else L("Nothing archived"),
                    message = if (tab.value == "active") L("Add one from the starting texts or write your own.") else L("Archived consents show here and can be restored."),
                )
            } else {
                TeamAdaptiveGrid(items = shown, minimum = 300.dp, spacing = 8.dp) { template ->
                    TemplateRow(template, requiredLabel(template)) { target = TeamConsentTarget(templateId = template.id) }
                }
                Text(
                    if (tab.value == "active") LP(shown.size, "1 active consent", "%d active consents") else LP(shown.size, "1 archived consent", "%d archived consents"),
                    style = Theme.text(12, FontWeight.SemiBold),
                    color = Theme.inkSoft,
                )
            }
        }
    }

    target?.let { open ->
        AppSheet(onDismissRequest = { target = null }) {
            androidx.compose.runtime.key(open.id) {
                TeamConsentTemplateEditor(
                    templateId = open.templateId,
                    canEdit = canEdit,
                    businessName = workspace.current?.name,
                    enabledCategories = workspace.current?.enabledCategories ?: emptyList(),
                    catalog = model.catalog,
                ) { load() }
            }
        }
    }
}

@Composable
private fun TemplateRow(template: TeamConsentTemplate, required: String?, onOpen: () -> Unit) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .plainButton(onClick = onOpen)
            .background(Theme.surface, box)
            .border(1.dp, Theme.borderSoft, box)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph("file-pen-line", 20.dp, tint = Theme.blue, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(template.title ?: "", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            FlowLayout(spacing = 4.dp) {
                Chip(text = TeamConsentRules.validityLabel(template.validity, template.validDays), tone = Chip.Tone.custom(Theme.inkSoft), percent = 14.0)
                Chip(text = (template.language ?: "es").uppercase(), tone = Chip.Tone.custom(Theme.inkSoft), percent = 14.0)
                if (template.needsAmount == true) Chip(text = L("Names an amount"), tone = Chip.Tone.custom(Theme.mango), icon = "coins", percent = 14.0)
                Chip(text = LF("Version %d", template.version ?: 1), tone = Chip.Tone.custom(Theme.inkSoft), percent = 14.0)
            }
            Text(
                (required?.let { LF("Required for: %@", it) } ?: L("Asked for when someone chooses it")) + " · " + LF("Changed %@", DateText.date(template.updatedAt)),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
        }
        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 4.dp))
    }
}

private class TeamConsentTarget(
    /** null = a new one. */
    val templateId: String?,
) {
    val id: UUID = UUID.randomUUID()
}

// MARK: - The editor

private data class TeamConsentDraft(
    val language: String,
    val title: String,
    val body: String,
    val validity: String,
    val validDays: String,
    val serviceIds: List<String>,
    val stayKinds: List<String>,
) {
    fun same(other: TeamConsentDraft): Boolean =
        language == other.language && title == other.title && body == other.body && validity == other.validity && validDays == other.validDays &&
            serviceIds.toSet() == other.serviceIds.toSet() && stayKinds.toSet() == other.stayKinds.toSet()

    companion object {
        fun of(language: String, title: String, body: String, validity: String, validDays: Int?, serviceIds: List<String> = emptyList(), stayKinds: List<String> = emptyList()) =
            TeamConsentDraft(language, title, body, validity, validDays?.toString() ?: "", serviceIds, stayKinds)
    }
}

@Composable
private fun TeamConsentTemplateEditor(
    templateId: String?,
    canEdit: Boolean,
    businessName: String?,
    enabledCategories: List<String>,
    catalog: TeamServiceCatalog?,
    onDone: suspend () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<TeamConsentTemplateDetail?>(null) }
    var detailFailed by remember { mutableStateOf<String?>(null) }
    var library by remember { mutableStateOf<List<TeamConsentLibraryEntry>?>(null) }
    var libraryFailed by remember { mutableStateOf(false) }
    val libraryLanguage = remember { mutableStateOf(if (LocalizationManager.currentLanguage == AppLanguage.english) "en" else "es") }
    var defaultKey by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<TeamConsentDraft?>(null) }
    var saved by remember { mutableStateOf<TeamConsentDraft?>(null) }
    // iOS `TextSelection?`: null until the person puts the cursor in the text (then a placeholder goes there).
    var selection by remember { mutableStateOf<TextRange?>(null) }
    var composition by remember { mutableStateOf<TextRange?>(null) }
    var preview by remember { mutableStateOf(false) }
    var openVersion by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val isNew = templateId == null
    val readOnly = !canEdit || detail?.status == "archived"
    val dirty = draft?.let { d ->
        saved?.let { !d.same(it) } ?: (d.title.trim().nonEmpty != null || d.body.trim().nonEmpty != null)
    } ?: false
    val wordsChanged = draft?.let { d ->
        saved?.let { s -> d.title.trim() != s.title.trim() || d.body.trim() != s.body.trim() || d.language != s.language }
    } ?: false
    val choosingStart = isNew && draft == null

    suspend fun loadLibrary() {
        try {
            library = API.get("/provider-consent-templates/library", query = mapOf("language" to libraryLanguage.value))
            libraryFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            libraryFailed = true
        }
    }

    suspend fun load() {
        if (templateId != null) {
            try {
                val loaded: TeamConsentTemplateDetail = API.get("/provider-consent-templates/$templateId")
                detail = loaded
                val next = TeamConsentDraft.of(
                    language = loaded.language ?: "es", title = loaded.title ?: "", body = loaded.body ?: "",
                    validity = loaded.validity ?: "per_act", validDays = loaded.validDays,
                    serviceIds = loaded.requiredFor?.serviceIds ?: emptyList(), stayKinds = loaded.requiredFor?.stayKinds ?: emptyList(),
                )
                saved = next
                // What is being typed stays; a first load fills the form.
                if (draft == null) draft = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detailFailed = errorText(e, fallback = L("The consent could not be loaded."))
            }
        } else if (canEdit) {
            loadLibrary()
        }
    }

    LaunchedEffect(Unit) { load() }
    // iOS `.onChange(of: libraryLanguage)`.
    val firstLanguage = remember { libraryLanguage.value }
    LaunchedEffect(libraryLanguage.value) { if (libraryLanguage.value != firstLanguage || library != null) loadLibrary() }

    fun start(entry: TeamConsentLibraryEntry?) {
        draft = if (entry != null) {
            TeamConsentDraft.of(language = entry.language ?: libraryLanguage.value, title = entry.title ?: "", body = entry.body ?: "", validity = entry.validity ?: "per_act", validDays = entry.validDays)
        } else {
            TeamConsentDraft.of(language = libraryLanguage.value, title = "", body = "", validity = "per_act", validDays = null)
        }
        defaultKey = entry?.key
        saved = null
        selection = null
        form.errors = emptyMap()
    }

    fun insert(key: String) {
        val current = draft ?: return
        if (readOnly) return
        val picked = selection?.takeIf { it.max <= current.body.length }
        val range = picked?.let { it.min until it.max }
        val next = TeamConsentRules.insert(key, into = current.body, range = range)
        val body = next.first.take(TeamConsentRules.bodyMax)
        draft = current.copy(body = body)
        form.clear("body")
        val caret = minOf(next.second, body.length)
        selection = TextRange(caret)
        composition = null
    }

    fun save() {
        val d = draft ?: return
        val title = d.title.trim()
        val body = d.body.trim()
        val daysText = d.validDays.trim()
        val days = daysText.toIntOrNull()
        val daysValid = daysText.isEmpty() || (days?.let { it in 1..TeamConsentRules.validDaysMax } ?: false)
        if (!form.check(listOf(
                "title" to (if (title.isEmpty()) L("Give the consent a title.") else if (title.length > TeamConsentRules.titleMax) LF("Keep the title under %d characters.", TeamConsentRules.titleMax) else null),
                "body" to (if (body.isEmpty()) L("Write the consent’s text.") else null),
                "validDays" to (if (d.validity == "standing" && !daysValid) L("Enter a number of days between 1 and 3650, or leave it empty.") else null),
            ))) return
        if (!isNew && !dirty) {
            dismiss()
            return
        }
        val validDays: Int? = if (d.validity == "standing") days else null
        val requiredFor = mapOf("serviceIds" to d.serviceIds, "stayKinds" to d.stayKinds)
        val input = LinkedHashMap<String, Any?>()
        val s = saved
        if (isNew) {
            defaultKey?.let { input["defaultKey"] = it }
            input["language"] = d.language
            input["title"] = title
            input["body"] = body
            input["validity"] = d.validity
            input["validDays"] = validDays
            input["requiredFor"] = requiredFor
        } else if (s != null) {
            if (d.language != s.language) input["language"] = d.language
            if (title != s.title.trim()) input["title"] = title
            if (body != s.body.trim()) input["body"] = body
            if (d.validity != s.validity) input["validity"] = d.validity
            if (d.validity != s.validity || d.validDays != s.validDays) input["validDays"] = validDays
            if (d.serviceIds.toSet() != s.serviceIds.toSet() || d.stayKinds.toSet() != s.stayKinds.toSet()) input["requiredFor"] = requiredFor
        }
        val changedWords = wordsChanged
        saving = true
        scope.launch {
            try {
                if (templateId != null) {
                    API.patch<EmptyResponse>("/provider-consent-templates/$templateId", input)
                    Toast.show(if (changedWords) L("Saved as a new version.") else L("Saved."))
                } else {
                    API.post<EmptyResponse>("/provider-consent-templates", input)
                    Toast.show(L("Consent added."))
                }
                onDone()
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("title" to "title", "body" to "body", "validDays" to "validDays"))
            } finally {
                saving = false
            }
        }
    }

    fun setArchived(archived: Boolean) {
        val id = templateId ?: return
        saving = true
        scope.launch {
            try {
                API.patch<EmptyResponse>("/provider-consent-templates/$id", mapOf("status" to (if (archived) "archived" else "active")))
                Toast.show(if (archived) L("Archived. It is no longer asked for, and signed ones keep counting.") else L("Active again."))
                onDone()
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(errorText(e, fallback = L("Could not change it. Try again.")))
            } finally {
                saving = false
            }
        }
    }

    val loadedDetail = detail
    val canSave = canEdit && !readOnly && !choosingStart
    ProviderModal(
        title = if (isNew) L("New consent") else (loadedDetail?.title ?: L("Consent")),
        subtitle = loadedDetail?.let { LF("Version %1\$d · %2\$d signed", it.version ?: 1, it.signedCount ?: 0) }
            ?: (if (isNew) L("Start from a blank page or one of the starting texts.") else null),
        systemImage = "file-pen-line",
        accent = ModalAccent.aqua,
        isDirty = canEdit && dirty,
        form = form,
        primaryTitle = if (canSave) (if (isNew) L("Add consent") else (if (wordsChanged) LF("Save as version %d", (loadedDetail?.version ?: 0) + 1) else L("Save"))) else null,
        isWorking = saving,
        // Choosing where to start has nothing to cancel yet: "Close", as on the web.
        cancelTitle = if (canEdit && !choosingStart) L("Cancel") else L("Close"),
        secondaryActions = if (canEdit && loadedDetail != null) listOf(
            if (loadedDetail.status == "archived") ModalAction(L("Restore"), icon = "archive-restore") { setArchived(false) }
            else ModalAction(L("Archive"), icon = "archive") { setArchived(true) }
        ) else emptyList(),
        primaryIcon = if (isNew) "file-plus-2" else "save",
        onPrimary = if (canSave) ({ save() }) else null,
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        NoticeCard(
            tone = NoticeCard.Tone.warning,
            title = L("Wording must be reviewed by a professional before you rely on it."),
            message = L("The starting texts follow public guidance (NOM-064-ZOO-2000, AVMA) but aren’t legal advice for your business."),
            icon = "scale",
            look = NoticeCard.Look.banner,
        )
        val current = draft
        if (!isNew && loadedDetail == null) {
            val error = detailFailed
            if (error != null) {
                Text(
                    error,
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                    textAlign = TextAlign.Center,
                )
            } else {
                TeamProgress(minHeight = 120.dp)
            }
        } else if (choosingStart) {
            StartPicker(library = library, libraryFailed = libraryFailed, libraryLanguage = libraryLanguage) { start(it) }
        } else if (current != null) {
            Editor(
                draft = current,
                setDraft = { draft = it },
                detail = loadedDetail,
                isNew = isNew,
                readOnly = readOnly,
                form = form,
                preview = preview,
                onTogglePreview = { preview = !preview },
                businessName = businessName,
                enabledCategories = enabledCategories,
                catalog = catalog,
                selection = selection,
                composition = composition,
                onSelection = { sel, comp -> selection = sel; composition = comp },
                onInsert = { insert(it) },
                openVersion = openVersion,
                onToggleVersion = { id -> openVersion = if (openVersion == id) null else id },
                onStartOver = { draft = null; defaultKey = null },
            )
        }
    }
}

// MARK: Starting point

@Composable
private fun StartPicker(
    library: List<TeamConsentLibraryEntry>?,
    libraryFailed: Boolean,
    libraryLanguage: androidx.compose.runtime.MutableState<String>,
    onStart: (TeamConsentLibraryEntry?) -> Unit,
) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .plainButton { onStart(null) }
                .dashedBorder(2.dp, Theme.borderSoft, Theme.boxRadius, 6.dp, 5.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(Theme.wash(Theme.blue, 12.0), CircleShape), contentAlignment = Alignment.Center) {
                Glyph("file-plus-2", 20.dp, tint = Theme.blue)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L("A blank consent"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                Text(L("Write your own words."), style = Theme.text(12), color = Theme.inkSoft)
            }
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("book-open", 16.dp, tint = Theme.blue)
                Text(L("Starting texts").uppercase(appLocale), style = Theme.text(12, FontWeight.Bold).tracking(1.7), color = Theme.blue)
            }
            Segmented(
                options = listOf(Segmented.Option("es", "Español"), Segmented.Option("en", "English")),
                selection = libraryLanguage,
                look = Segmented.Look.solid,
                label = L("Language"),
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
        if (library == null && !libraryFailed) {
            TeamProgress(minHeight = 80.dp)
        } else if (libraryFailed) {
            Text(
                L("The starting texts could not be loaded. Start from a blank one, or try again later."),
                Modifier
                    .fillMaxWidth()
                    .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                style = Theme.text(14),
                color = Theme.inkSoft,
                textAlign = TextAlign.Center,
            )
        } else {
            (library ?: emptyList()).forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainButton { onStart(entry) }
                        .border(1.dp, Theme.borderSoft, box)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("file-pen-line", 20.dp, tint = Theme.blue)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.title ?: "", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                        FlowLayout(spacing = 4.dp) {
                            Chip(text = TeamConsentRules.validityLabel(entry.validity, entry.validDays), tone = Chip.Tone.custom(Theme.inkSoft), percent = 14.0)
                            if (TeamConsentRules.needsAmount(entry.title ?: "", entry.body ?: "")) {
                                Chip(text = L("Names an amount"), tone = Chip.Tone.custom(Theme.mango), percent = 14.0)
                            }
                        }
                    }
                    Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
                }
            }
        }
    }
}

// MARK: The words and the rules

@Composable
private fun Editor(
    draft: TeamConsentDraft,
    setDraft: (TeamConsentDraft) -> Unit,
    detail: TeamConsentTemplateDetail?,
    isNew: Boolean,
    readOnly: Boolean,
    form: FormState,
    preview: Boolean,
    onTogglePreview: () -> Unit,
    businessName: String?,
    enabledCategories: List<String>,
    catalog: TeamServiceCatalog?,
    selection: TextRange?,
    composition: TextRange?,
    onSelection: (TextRange?, TextRange?) -> Unit,
    onInsert: (String) -> Unit,
    openVersion: String?,
    onToggleVersion: (String) -> Unit,
    onStartOver: () -> Unit,
) {
    val box = RoundedCornerShape(Theme.boxRadius)
    if (detail?.status == "archived") {
        Text(
            L("Archived: it isn’t asked for any more. Restore it to change it."),
            Modifier
                .fillMaxWidth()
                .background(Theme.surfaceMuted, box)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            style = Theme.text(14, FontWeight.SemiBold),
            color = Theme.inkSoft,
        )
    }
    if (isNew) {
        Row(Modifier.plainButton(onClick = onStartOver), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("arrow-left", 16.dp, tint = Theme.blue)
            Text(L("Start from something else"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
        }
    }
    Disabled(readOnly) {
        PMTextField(
            L("Title"),
            text = stateOf({ draft.title }, { setDraft(draft.copy(title = it)); form.clear("title") }),
            key = "title",
            form = form,
            prompt = L("e.g. Consent for surgery and anesthesia"),
        )
    }
    FormField(label = L("Language")) {
        Disabled(readOnly) {
            Segmented(
                options = listOf(Segmented.Option("es", "Español"), Segmented.Option("en", "English")),
                selection = stateOf({ draft.language }, { if (!readOnly) setDraft(draft.copy(language = it)) }),
                look = Segmented.Look.solid,
                label = L("Language"),
            )
        }
    }
    if (preview) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L("Preview with sample values"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            PreviewCard(draft, businessName)
        }
    } else {
        val count = NumberFormat.getIntegerInstance(appLocale)
        FormField(
            label = L("Text"),
            key = "body",
            hint = LF("%1\$@ of %2\$@ characters", count.format(draft.body.length), count.format(TeamConsentRules.bodyMax)),
            form = form,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!readOnly) {
                    FlowLayout(spacing = 6.dp) {
                        TeamConsentRules.placeholders.forEach { key ->
                            Row(
                                Modifier
                                    .plainButton(label = LF("Put in %@", TeamConsentRules.placeholderLabel(key))) { onInsert(key) }
                                    .height(32.dp)
                                    .background(Theme.wash(Theme.blue, 8.0), CapsuleShape)
                                    .border(1.dp, Theme.mix(Theme.blue, 40.0, Theme.borderSoft), CapsuleShape)
                                    .padding(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("{$key}", style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp), color = Theme.inkStrong)
                                Text(TeamConsentRules.placeholderLabel(key), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                            }
                        }
                    }
                }
                val ink = Theme.inkStrong
                val promptInk = Theme.inkSoft
                val value = TextFieldValue(
                    text = draft.body,
                    selection = (selection ?: TextRange(draft.body.length)).let { TextRange(it.start.coerceIn(0, draft.body.length), it.end.coerceIn(0, draft.body.length)) },
                    composition = composition?.takeIf { it.max <= draft.body.length },
                )
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        val text = next.text.take(TeamConsentRules.bodyMax)
                        if (text != draft.body) {
                            setDraft(draft.copy(body = text))
                            form.clear("body")
                        }
                        onSelection(next.selection, next.composition)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp)
                        .fieldChrome(invalid = form.error("body") != null, multiline = true),
                    enabled = !readOnly,
                    textStyle = Theme.text(14).copy(color = ink),
                    cursorBrush = SolidColor(Theme.blue),
                    decorationBox = { inner ->
                        Box {
                            if (draft.body.isEmpty()) {
                                Text(L("Write what the client agrees to. Use the placeholders for the pet, the client, the date…"), style = Theme.text(14), color = promptInk)
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }

    PMButton(if (preview) L("Back to the text") else L("See the preview"), variant = ButtonVariant.outline, size = ButtonSize.compact, action = onTogglePreview)

    ModalSection(title = L("How long a signature lasts")) {
        ChoiceCards(
            options = listOf(
                Triple("per_act", L("Each visit or stay"), L("Signed again every time, e.g. a surgery or a sedation.")),
                Triple("standing", L("Once per pet"), L("Signed once and valid for the days you set, e.g. daycare rules.")),
            ),
            selection = stateOf({ draft.validity }, { if (!readOnly) setDraft(draft.copy(validity = it)) }),
        )
        if (draft.validity == "standing") {
            Disabled(readOnly) {
                PMTextField(
                    L("Valid for (days)"),
                    text = stateOf({ draft.validDays }, { value -> setDraft(draft.copy(validDays = value.filter { it.isDigit() }.take(4))); form.clear("validDays") }),
                    key = "validDays",
                    form = form,
                    prompt = "365",
                    hint = L("Leave it empty to keep it until it is revoked."),
                    keyboard = androidx.compose.ui.text.input.KeyboardType.Number,
                )
            }
        }
    }

    ModalSection(
        title = L("Required before it starts"),
        subtitle = L("A visit with one of these services, or a stay of these kinds, can’t start until it is signed. Owners and admins can still start with a reason."),
    ) {
        Text(L("Stays"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
        FlowLayout(spacing = 6.dp) {
            TeamConsentRules.stayKinds.forEach { kind ->
                FilterChip(title = TeamConsentRules.stayKindLabel(kind), isSelected = draft.stayKinds.contains(kind)) {
                    if (readOnly) return@FilterChip
                    setDraft(draft.copy(stayKinds = if (draft.stayKinds.contains(kind)) draft.stayKinds.filter { it != kind } else draft.stayKinds + kind))
                }
            }
        }
        if (catalog == null) TeamProgress()
        serviceGroups(draft, catalog, enabledCategories).forEach { (_, label, services) ->
            Text(label, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
            FlowLayout(spacing = 6.dp) {
                services.forEach { service ->
                    FilterChip(title = service.label, isSelected = draft.serviceIds.contains(service.id)) {
                        if (readOnly) return@FilterChip
                        setDraft(draft.copy(serviceIds = if (draft.serviceIds.contains(service.id)) draft.serviceIds.filter { it != service.id } else draft.serviceIds + service.id))
                    }
                }
            }
        }
        val known = (catalog?.services ?: emptyList()).map { it.id }.toSet()
        val unknown = draft.serviceIds.filter { !known.contains(it) }
        if (catalog != null && unknown.isNotEmpty()) {
            Text(L("Other services"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
            FlowLayout(spacing = 6.dp) {
                unknown.forEach { id ->
                    FilterChip(title = id, isSelected = true) { if (!readOnly) setDraft(draft.copy(serviceIds = draft.serviceIds.filter { it != id })) }
                }
            }
        }
    }

    val versions = detail?.versions
    if (versions != null && versions.isNotEmpty()) {
        ModalSection(title = L("Versions"), subtitle = L("Each saved wording is a version. Consents already signed keep the exact words they were signed with.")) {
            versions.forEach { version ->
                androidx.compose.runtime.key(version.id) {
                    VersionRow(version, inUse = version.version == detail.version, expanded = openVersion == version.id) { onToggleVersion(version.id) }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(draft: TeamConsentDraft, businessName: String?) {
    val samples = TeamConsentRules.samples(draft.language, business = businessName)
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.aqua, 6.0), box)
            .border(1.dp, Theme.borderSoft, box)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(TeamConsentRules.render(draft.title, samples).nonEmpty ?: L("Untitled"), style = Theme.heading(18), color = Theme.inkStrong)
        val text = TeamConsentRules.render(draft.body, samples).nonEmpty
        if (text != null) {
            Text(text, style = Theme.text(14), color = Theme.inkStrong)
        } else {
            Text(L("The text shows here as the client will read it."), style = Theme.text(14), color = Theme.inkSoft)
        }
        if (TeamConsentRules.needsAmount(draft.title, draft.body)) {
            Text(L("It names {amount}: whoever asks for it enters the amount first."), style = Theme.text(12), color = Theme.inkSoft)
        }
    }
}

@Composable
private fun VersionRow(version: TeamConsentVersion, inUse: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val box = RoundedCornerShape(Theme.boxRadius)
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "version")
    Column(Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, box)) {
        Row(
            Modifier
                .fillMaxWidth()
                .plainButton(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("history", 16.dp, tint = Theme.inkSoft)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(LF("Version %d", version.version ?: 1), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    if (inUse) {
                        Text(
                            L("In use"),
                            Modifier
                                .background(Theme.wash(Theme.lime, 35.0), CapsuleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = Theme.text(10.6, FontWeight.ExtraBold),
                            color = Theme.inkStrong,
                        )
                    }
                }
                Text(
                    listOfNotNull(DateText.dayTime(version.createdAt), version.createdByName.nonEmpty, version.language?.uppercase()).joinToString(" · "),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .teamEdgeRule(Theme.borderSoft, top = true)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(version.title ?: "", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                Text(version.body ?: "", style = Theme.text(14), color = Theme.inkStrong)
            }
        }
    }
}

/** Services grouped by kind: the ones this business offers, plus any already chosen — (id, label, services). */
private fun serviceGroups(draft: TeamConsentDraft, catalog: TeamServiceCatalog?, enabledCategories: List<String>): List<Triple<String, String, List<TeamServiceCatalog.Service>>> {
    val services = catalog?.services ?: emptyList()
    val chosen = draft.serviceIds.toSet()
    val offered = enabledCategories.toSet()
    return (catalog?.categories ?: emptyList()).mapNotNull { category ->
        val list = services.filter { it.category == category.id && (offered.contains(category.id) || chosen.contains(it.id)) }
        if (list.isEmpty()) null else Triple(category.id, category.label ?: category.id, list)
    }
}
