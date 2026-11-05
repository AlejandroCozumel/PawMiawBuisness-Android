package com.pawmiaw.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

//
//  TeamMemberCredentials.kt (iOS TeamMemberCredentials.swift)
//
//  A team member's professional credentials (web:
//  components/app/prescriptions/credentials-card.tsx and credential-sheet.tsx):
//  type, number, state, whether it is verified and whether it lets them
//  prescribe here; add one or change it, declare it, record the registry
//  check, remove it. At a clinic it is what lets someone sign prescriptions;
//  elsewhere it lists certifications. Every change is audited by the server.
//  The add/change sheet is the one credential sheet: Home's welcome and
//  setup checklist open it for the person's own ("me") credentials too.
//

@Serializable
data class TeamCredential(
    val id: String,
    val type: String? = null,
    val label: Words? = null,
    val number: String? = null,
    val region: String? = null,
    val registeredAddress: String? = null,
    val issuer: String? = null,
    val title: String? = null,
    val expiresOn: String? = null,
    val status: String? = null,
    val verified: Boolean? = null,
    val prescribesHere: Boolean? = null,
    val allowsControlled: Boolean? = null,
    val registry: Registry? = null,
    val declaredAt: String? = null,
    val registryCheckedAt: String? = null,
    val registryCheckedByName: String? = null,
) {
    @Serializable
    data class Words(val es: String? = null, val en: String? = null)

    @Serializable
    data class Registry(val url: String? = null, val label: String? = null)
}

@Serializable
private data class TeamMemberCredentials(
    val items: List<TeamCredential>? = null,
    val canPrescribe: Boolean? = null,
)

/** The credential types and the rules the form shares with the server (web: lib/prescriptions.ts). */
object TeamCredentialRules {
    val types = listOf("cedula_profesional", "state_license", "dea", "other_license", "state_controlled", "usda_accreditation", "vet_tech_license", "certification")
    /** Types that carry a US state. */
    val stateTypes: Set<String> = setOf("state_license", "state_controlled", "vet_tech_license")
    /** A US veterinary technician's designation. */
    val designations = listOf("RVT", "LVT", "CVT", "LVMT")

    fun label(type: String): String = when (type) {
        "cedula_profesional" -> L("Cédula profesional")
        "state_license" -> L("State license")
        "dea" -> L("DEA number")
        "other_license" -> L("Other professional license")
        "state_controlled" -> L("State controlled-substance registration")
        "usda_accreditation" -> L("USDA accreditation")
        "vet_tech_license" -> L("Vet tech license (RVT, LVT, CVT)")
        "certification" -> L("Certification")
        else -> type
    }

    /** Offered first in this country; the rest follow. */
    fun suggested(country: String?): List<String> = when (country?.uppercase()) {
        "MX" -> listOf("cedula_profesional", "other_license")
        "US" -> listOf("state_license", "dea", "state_controlled", "usda_accreditation", "other_license")
        else -> listOf("other_license")
    }

    fun ordered(country: String?): List<String> {
        val first = suggested(country)
        return first + types.filter { !first.contains(it) }
    }

    fun numberLabel(type: String): String = when (type) {
        "cedula_profesional" -> L("Cédula number")
        "dea" -> L("DEA number")
        "state_controlled" -> L("Registration number")
        "usda_accreditation" -> L("Accreditation number")
        "certification" -> L("Number (optional)")
        else -> L("License number")
    }

    fun note(type: String): String? = when (type) {
        "dea" -> L("Only for controlled substances; it is printed only on those prescriptions.")
        "state_controlled" -> L("Optional. Some states ask for their own registration, besides the DEA, to prescribe controlled substances.")
        "usda_accreditation" -> L("Optional. For official health certificates (travel, interstate and export).")
        "vet_tech_license" -> L("Optional. Your credential as a veterinary technician; it doesn’t let you prescribe.")
        "certification" -> L("Optional. Shown on your profile in this workspace.")
        else -> null
    }

    /** (url, label). */
    fun registry(type: String): Pair<String, String>? = when (type) {
        "cedula_profesional" -> "https://www.cedulaprofesional.sep.gob.mx/" to L("Look it up in the Registro Nacional de Profesionistas (SEP)")
        "state_license", "vet_tech_license" -> "https://www.aavsb.org/public-resources/look-up-a-license/" to L("Look it up with the state board (AAVSB directory)")
        "dea" -> "https://www.deadiversion.usdoj.gov/" to L("DEA Diversion Control Division")
        "usda_accreditation" -> "https://www.aphis.usda.gov/nvap" to L("USDA National Veterinary Accreditation Program")
        else -> null
    }

    /** (code, name). */
    val usStates: List<Pair<String, String>> = listOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California", "CO" to "Colorado",
        "CT" to "Connecticut", "DE" to "Delaware", "DC" to "District of Columbia", "FL" to "Florida", "GA" to "Georgia",
        "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
        "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts",
        "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana",
        "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico",
        "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
        "OR" to "Oregon", "PA" to "Pennsylvania", "PR" to "Puerto Rico", "RI" to "Rhode Island", "SC" to "South Carolina",
        "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont", "VA" to "Virginia",
        "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
    )

    /** The state a US license starts from: the two-letter code or the name of a state in the business's address. */
    fun state(fromAddress: String?): String? {
        val address = fromAddress.nonEmpty ?: return null
        val codes = usStates.map { it.first }.toSet()
        val upper = address.uppercase()
        val found = Regex("\\b([A-Z]{2})\\b(?:\\s+\\d{5})?").findAll(upper).map { it.groupValues[1] }.filter { codes.contains(it) }.toList()
        found.lastOrNull()?.let { return it }
        val lower = address.lowercase()
        return usStates.firstOrNull { lower.contains(it.second.lowercase()) }?.first
    }

    /** A DEA number: two letters and seven digits, the last one a check digit. */
    fun deaValid(value: String): Boolean {
        val cleaned = value.replace(" ", "").replace("-", "").uppercase()
        val match = Regex("([A-Z])([A-Z9])(\\d{7})").matchEntire(cleaned) ?: return false
        if (!"ABCDEFGHJKLMPRSTUX".contains(match.groupValues[1])) return false
        val d = match.groupValues[3].map { it.digitToInt() }
        if (d.size != 7) return false
        return (d[0] + d[2] + d[4] + 2 * (d[1] + d[3] + d[5])) % 10 == d[6]
    }

    /** The field a problem belongs to and what the form says before the server does: (field, message). */
    fun problem(type: String, number: String, region: String, address: String, issuer: String, title: String): Pair<String, String>? {
        fun message(code: String): String = this.message(code) ?: L("The credential couldn’t be saved.")
        val trimmed = number.trim()
        if (type == "certification") {
            return if (title.trim().length < 2) "title" to message("title_required") else null
        }
        if (trimmed.isEmpty()) return "number" to message("number_required")
        if ((type == "state_controlled" || type == "vet_tech_license") && region.isEmpty()) return "region" to message("state_required")
        if (type == "vet_tech_license" && !designations.contains(issuer.trim().uppercase())) return "designation" to message("designation_invalid")
        if (type == "cedula_profesional") {
            val digits = trimmed.filter { !" .-".contains(it) }
            if (!Regex("\\d{6,8}").matches(digits)) return "number" to message("cedula_format")
        }
        if (type == "state_license" && region.isEmpty()) return "region" to message("state_required")
        if (type == "dea" && !deaValid(trimmed)) return "number" to message("dea_format")
        if (type == "dea" && address.trim().isEmpty()) return "address" to message("dea_address_required")
        if (type == "other_license" && issuer.trim().isEmpty()) return "issuer" to message("issuer_required")
        return null
    }

    /** The server's refusals, by code (web: PRESCRIPTION_ERROR_MESSAGES). */
    fun message(code: String): String? = when (code) {
        "number_required" -> L("Type the number.")
        "cedula_format" -> L("A cédula profesional has 6 to 8 digits.")
        "state_required" -> L("Choose the state that issued the license.")
        "state_invalid" -> L("That isn’t a US state.")
        "license_format" -> L("That license number doesn’t look right.")
        "dea_format" -> L("That isn’t a valid DEA number (two letters and seven digits).")
        "dea_address_required" -> L("Add the address the DEA registration is for.")
        "issuer_required" -> L("Say who issued the license.")
        "other_format" -> L("The number must have 3 to 40 characters.")
        "usda_format" -> L("That USDA accreditation number doesn’t look right.")
        "designation_invalid" -> L("Choose RVT, LVT, CVT or LVMT.")
        "title_required" -> L("Type the certification’s name.")
        "expires_invalid" -> L("The expiry date must be a date.")
        "credential_exists" -> L("This credential is already on the list.")
        "declaration_self_only" -> L("Only the professional can declare a credential is theirs.")
        "forbidden" -> L("You don’t have permission to do this.")
        else -> null
    }

    /** Which field a refusal code points at. */
    fun field(code: String): String? = when (code) {
        "number_required", "cedula_format", "license_format", "dea_format", "other_format", "usda_format" -> "number"
        "state_required", "state_invalid" -> "region"
        "dea_address_required" -> "address"
        "issuer_required" -> "issuer"
        "designation_invalid" -> "designation"
        "title_required" -> "title"
        "expires_invalid" -> "expires"
        else -> null
    }
}

/** A credential to add (null) or change, as the sheet's item (iOS `TeamCredentialTarget`, a fresh id each time). */
private class TeamCredentialTarget(val credential: TeamCredential?)

@Composable
fun TeamCredentialsCard(
    memberId: String,
    isSelf: Boolean,
    canEdit: Boolean,
    /** A clinic: say whether they can prescribe. Elsewhere it lists certifications. */
    prescribing: Boolean,
    /** Their name, for the sheet's "For …" line. */
    memberName: String? = null,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<TeamMemberCredentials?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<TeamCredential?>(null) }
    var sheet by remember { mutableStateOf<TeamCredentialTarget?>(null) }
    val editable = canEdit || isSelf

    suspend fun load() {
        try {
            data = API.get<TeamMemberCredentials>("/provider-members/$memberId/credentials")
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = data == null
        }
    }

    fun name(credential: TeamCredential): String {
        if (credential.type == "certification") credential.title.nonEmpty?.let { return it }
        val words = if (LocalizationManager.currentLanguage == AppLanguage.spanish) credential.label?.es else credential.label?.en
        return words.nonEmpty ?: credential.type?.let { TeamCredentialRules.label(it) } ?: ""
    }

    fun act(credential: TeamCredential, action: String) {
        busy = credential.id
        scope.launch {
            try {
                API.send("POST", "/provider-members/$memberId/credentials/${credential.id}/$action", emptyMap())
                Toast.show(if (action == "declare") L("Declared. The credential is verified.") else L("Registry check recorded."))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error((e as? APIError)?.code?.let { TeamCredentialRules.message(it) } ?: errorText(e))
            } finally {
                busy = null
            }
        }
    }

    LaunchedEffect(memberId) { load() }

    TeamPanel(
        title = if (prescribing) L("Professional credentials") else L("Certifications and licenses"),
        subtitle = if (!prescribing) L("Shown on this profile in the workspace.") else if (data?.canPrescribe ?: false) L("Can sign prescriptions here.") else L("Needs a credential valid in this country to sign prescriptions."),
        systemImage = "id-card",
        tint = Theme.aqua,
        modifier = modifier,
        action = {
            if (editable) {
                PMButton(L("Add"), systemImage = "plus", variant = ButtonVariant.outline) { sheet = TeamCredentialTarget(credential = null) }
            }
        },
    ) {
        if (data == null) {
            if (failed) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(L("Something went wrong. Try again."), style = Theme.text(14), color = Theme.inkSoft)
                    PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { load() } }
                }
            } else TeamProgress(minHeight = 64.dp)
        } else if ((data?.items ?: emptyList()).isEmpty()) {
            EmptyState(
                systemImage = "id-card",
                title = if (isSelf && prescribing) L("Add your cédula profesional or license so you can sign prescriptions.") else L("No credentials yet."),
                accent = Theme.pink,
            )
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (data?.items ?: emptyList()).forEach { credential ->
                    CredentialRow(
                        credential = credential,
                        name = name(credential),
                        isSelf = isSelf,
                        editable = editable,
                        busy = busy == credential.id,
                        openRegistry = { openURL(it) },
                        act = { act(credential, it) },
                        change = { sheet = TeamCredentialTarget(credential = credential) },
                        remove = { removing = credential },
                    )
                }
            }
        }
        TeamLegalReviewNote()
    }

    removing?.let { credential ->
        AppSheet(onDismissRequest = { removing = null }) {
            ConfirmSheet(title = L("Remove"), message = name(credential), confirmTitle = L("Remove"), destructive = true) { _ ->
                API.send("DELETE", "/provider-members/$memberId/credentials/${credential.id}")
                Toast.show(L("Credential removed."))
                load()
            }
        }
    }
    sheet?.let { target ->
        AppSheet(onDismissRequest = { sheet = null }) {
            TeamCredentialSheet(
                memberId = memberId,
                memberName = memberName,
                isSelf = isSelf,
                country = workspace.current?.country,
                businessAddress = workspace.current?.address,
                credential = target.credential,
            ) { load() }
        }
    }
}

@Composable
private fun CredentialRow(
    credential: TeamCredential,
    name: String,
    isSelf: Boolean,
    editable: Boolean,
    busy: Boolean,
    openRegistry: (String) -> Unit,
    act: (String) -> Unit,
    change: () -> Unit,
    remove: () -> Unit,
) {
    val facts = listOfNotNull(
        credential.issuer.nonEmpty,
        credential.registeredAddress.nonEmpty,
        credential.expiresOn?.let { LF("Expires %@", DateText.date(it)) },
        credential.declaredAt?.let { LF("Declared %@", DateText.date(it)) },
        if (credential.registryCheckedAt != null) LF("Checked in the registry by %@", credential.registryCheckedByName ?: "—") else null,
    )
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Theme.borderSoft, box)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowLayout(spacing = 8.dp) {
            Text(name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
            TeamCredentialStatusChip(status = credential.status, verified = credential.verified)
            if (credential.prescribesHere ?: false) Chip(text = L("Prescribes here"), tone = Chip.Tone.custom(Theme.blue), icon = "badge-check", percent = 14.0, weight = FontWeight.ExtraBold)
            if (credential.allowsControlled ?: false) Chip(text = L("Controlled substances"), tone = Chip.Tone.custom(Theme.coral), percent = 14.0, weight = FontWeight.ExtraBold)
        }
        if (credential.number.nonEmpty != null || credential.region.nonEmpty != null) {
            Text(
                listOfNotNull(credential.region.nonEmpty, credential.number.nonEmpty).joinToString(" · "),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                color = Theme.inkStrong,
            )
        }
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = Theme.text(12), color = Theme.inkSoft)
        FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 4.dp)) {
            val link = credential.registry?.url
            if (link != null) {
                PMButton(L("Official registry"), systemImage = "external-link", variant = ButtonVariant.outline, size = ButtonSize.compact) { openRegistry(link) }
            }
            if (isSelf && credential.declaredAt == null) {
                PMButton(L("Declare it is mine"), systemImage = "badge-check", size = ButtonSize.compact, isWorking = busy) { act("declare") }
            }
            if (editable && credential.registry != null && credential.registryCheckedAt == null) {
                PMButton(L("I checked the registry"), systemImage = "search-check", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = busy) { act("registry-check") }
            }
            if (editable) {
                PMButton(L("Change"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact, action = change)
                PMButton(L("Remove"), systemImage = "trash-2", variant = ButtonVariant.destructive, size = ButtonSize.compact, action = remove)
            }
        }
    }
}

/** A credential's state, the same wherever it is listed (a staff page, the welcome). */
@Composable
fun TeamCredentialStatusChip(status: String?, verified: Boolean? = null, modifier: Modifier = Modifier) {
    // The web's CredentialStatusChip: the state's tone at 26%, its icon, 11.2 extrabold.
    @Composable
    fun chip(text: String, tone: androidx.compose.ui.graphics.Color, icon: String) =
        Chip(text = text, tone = Chip.Tone.custom(tone), icon = icon, percent = 26.0, weight = FontWeight.ExtraBold, modifier = modifier)
    when (status) {
        "verified" -> chip(L("Verified"), Theme.lime, "badge-check")
        "unverified" -> chip(L("Not declared yet"), Theme.mango, "circle-dashed")
        "expired" -> chip(L("Expired@credential"), Theme.coral, "file-warning")
        "invalid" -> chip(L("Check the number"), Theme.coral, "file-warning")
        "removed" -> chip(L("Removed@credential"), Theme.inkSoft, "file-warning")
        else -> if (verified == true) chip(L("Verified"), Theme.lime, "badge-check") else chip(L("Not declared yet"), Theme.mango, "circle-dashed")
    }
}

/** The legal wording and fields follow public sources and must be reviewed by a professional (the app says so). */
@Composable
fun TeamLegalReviewNote(modifier: Modifier = Modifier) {
    Text(
        L("The prescription fields and wording follow public sources (NOM-064-ZOO-2000 in Mexico; the AVMA, the DEA and state boards in the United States). Have a professional review them before you rely on them."),
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.mango, 12.0), RoundedCornerShape(Theme.chipRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = Theme.text(12),
        color = Theme.inkStrong,
    )
}

@Serializable
private data class TeamCredentialSaved(
    val id: String,
    val verified: Boolean? = null,
)

/** SwiftUI `.onChange(of: value)`: runs on each change after the first composition. */
@Composable
private fun <T> OnChange(value: T, action: (T) -> Unit) {
    val latest by rememberUpdatedState(action)
    val previous = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        if (previous[0] != value) {
            previous[0] = value
            latest(value)
        }
    }
}

/**
 * Adds (or changes) a professional credential. The country decides what is
 * offered first: the cédula profesional in Mexico, a state license (the
 * state taken from the business's address) and the DEA number in the US,
 * another license anywhere else. Only the professional declares it is
 * theirs: that declaration, with the format check and the registry link,
 * makes it "verificada". `memberId` "me" is the person's own (Home's
 * welcome and checklist); `types` narrows it to what a place asks for.
 */
@Composable
fun TeamCredentialSheet(
    memberId: String,
    memberName: String? = null,
    isSelf: Boolean,
    country: String?,
    businessAddress: String?,
    credential: TeamCredential? = null,
    /** Only these types (the welcome's "Add" for one type); null = every type, the country's first. */
    types: List<String>? = null,
    /** Why it is asked, shown first. */
    reason: String? = null,
    modifier: Modifier = Modifier,
    onSaved: suspend () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val editing = credential != null
    val offered = types?.takeIf { it.isNotEmpty() } ?: TeamCredentialRules.ordered(country)

    // iOS `.onAppear(perform: start)`: the fields start from the credential, else the country's first type.
    var type by remember { mutableStateOf(credential?.type ?: offered.firstOrNull() ?: "other_license") }
    val number = remember { mutableStateOf(credential?.number ?: "") }
    val region = remember { mutableStateOf(credential?.region ?: TeamCredentialRules.state(fromAddress = businessAddress) ?: "") }
    val address = remember { mutableStateOf(credential?.registeredAddress ?: (businessAddress ?: "")) }
    val issuer = remember { mutableStateOf(credential?.issuer ?: "") }
    val title = remember { mutableStateOf(credential?.title ?: "") }
    val expires = remember { mutableStateOf<Instant?>(credential?.expiresOn?.let { TeamDays.picked(it.take(10)) }) }
    var declare by remember { mutableStateOf(isSelf && credential == null) }
    var saving by remember { mutableStateOf(false) }

    val hasState = TeamCredentialRules.stateTypes.contains(type)
    /** A vet tech's designation lives where other licenses keep their issuer. */
    val designation = if (type == "vet_tech_license") (issuer.value.trim().uppercase().nonEmpty ?: "RVT") else issuer.value
    val expiresKey = expires.value?.let { TeamDays.keyPicked(it) }
    val dirty = if (credential != null) {
        number.value != (credential.number ?: "") || region.value != (credential.region ?: "") || address.value != (credential.registeredAddress ?: "") ||
            issuer.value != (credential.issuer ?: "") || title.value != (credential.title ?: "") || expiresKey != credential.expiresOn
    } else {
        number.value.isNotEmpty() || issuer.value.isNotEmpty() || title.value.isNotEmpty() || expires.value != null
    }

    OnChange(number.value) { form.clear("number") }
    OnChange(issuer.value) { form.clear("issuer"); form.clear("designation") }
    OnChange(title.value) { form.clear("title") }
    OnChange(address.value) { form.clear("address") }

    fun save() {
        val problem = TeamCredentialRules.problem(type, number.value, region.value, address.value, designation, title.value)
        if (problem != null) {
            form.check(listOf(problem.first to problem.second))
            return
        }
        val trimmedIssuer = issuer.value.trim()
        val body = mutableMapOf<String, Any?>(
            "number" to number.value.trim(),
            "region" to (if (hasState) region.value else null),
            "registeredAddress" to (if (type == "dea") address.value.trim() else null),
            "issuer" to (if (type == "other_license" || type == "certification") trimmedIssuer.nonEmpty else (if (type == "vet_tech_license") designation.trim() else null)),
            "expiresOn" to expiresKey,
        )
        if (type == "certification") body["title"] = title.value.trim()
        if (!editing) {
            body["type"] = type
            body["declare"] = isSelf && declare
        }
        saving = true
        scope.launch {
            try {
                val saved: TeamCredentialSaved = if (credential != null) {
                    API.patch("/provider-members/$memberId/credentials/${credential.id}", body)
                } else {
                    API.post("/provider-members/$memberId/credentials", body)
                }
                Toast.show(if (saved.verified ?: false) L("Credential saved and verified.") else L("Credential saved."))
                onSaved()
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = (e as? APIError)?.code
                val field = code?.let { TeamCredentialRules.field(it) }
                val message = code?.let { TeamCredentialRules.message(it) }
                if (field != null && message != null) {
                    form.check(listOf(field to message))
                } else if (message != null) {
                    Toast.error(message)
                } else {
                    Toast.error(errorText(e, fallback = L("The credential couldn’t be saved.")))
                }
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = if (editing) L("Change credential") else L("Add a professional credential"),
        subtitle = memberName.nonEmpty?.let { LF("For %@", it) } ?: (if (offered.size == 1 && !editing) TeamCredentialRules.label(type) else null),
        systemImage = "id-card",
        accent = ModalAccent.aqua,
        isDirty = dirty,
        form = form,
        primaryTitle = if (editing) L("Save changes") else L("Save credential"),
        isWorking = saving,
        primaryIcon = "badge-check",
        onPrimary = { save() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        if (reason != null) NoticeCard(tone = NoticeCard.Tone.info, title = reason)
        if (!editing) {
            if (offered.size > 1) {
                FormField(L("Type"), key = "type", form = form) {
                    FlowLayout(spacing = 6.dp) {
                        offered.forEach { value ->
                            FilterChip(title = TeamCredentialRules.label(value), isSelected = type == value) {
                                type = value
                                form.errors = emptyMap()
                            }
                        }
                    }
                }
            }
            TeamCredentialRules.note(type)?.let { note ->
                Text(note, style = Theme.text(12), color = Theme.inkSoft)
            }
        }
        if (type == "certification") {
            PMTextField(L("Certification"), text = title, key = "title", form = form, prompt = L("e.g. Fear Free Certified, Certified Master Groomer"), capitalization = KeyboardCapitalization.Words)
            PMTextField(L("Issued by"), text = issuer, key = "issuer", form = form, prompt = L("e.g. NDGAA, Fear Free, CCPDT"), hint = L("Optional."))
        }
        if (type == "vet_tech_license") {
            MenuField(
                label = L("Designation"),
                options = TeamCredentialRules.designations.map { it to it },
                selection = stateOf({ designation }, { issuer.value = it }),
                key = "designation",
                form = form,
            )
        }
        PMTextField(
            TeamCredentialRules.numberLabel(type),
            text = number,
            key = "number",
            form = form,
            prompt = if (type == "cedula_profesional") "1234567" else if (type == "dea") "AB1234563" else null,
            keyboard = if (type == "cedula_profesional") KeyboardType.Number else KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.Characters,
        )
        if (hasState) {
            MenuField(
                label = L("State"),
                options = listOf("" to L("Choose the state")) + TeamCredentialRules.usStates,
                selection = region,
                key = "region",
                form = form,
            )
        }
        if (type == "other_license") {
            PMTextField(L("Issued by"), text = issuer, key = "issuer", form = form, prompt = L("e.g. Colegio de Médicos Veterinarios"))
        }
        OptionalDateField(
            label = L("Expires"),
            date = expires,
            placeholder = L("Choose a day"),
            zone = ZoneId.systemDefault(),
            defaultDate = ZonedDateTime.now().plusYears(1).toInstant(),
            key = "expires",
            form = form,
            hint = L("Optional. An expired credential stops counting."),
        )
        if (type == "dea") {
            PMTextField(L("Address of the DEA registration"), text = address, key = "address", form = form, hint = L("Printed with the DEA number on controlled prescriptions."))
        }
        TeamCredentialRules.registry(type)?.let { (url, label) ->
            Row(
                Modifier.plainButton { openURL(url) },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("external-link", 14.dp, tint = Theme.blue)
                Text(label, style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
            }
        }
        if (!editing && isSelf) {
            val box = RoundedCornerShape(Theme.boxRadius)
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainButton { declare = !declare }
                    .border(1.dp, Theme.borderSoft, box)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Tick(on = declare, modifier = Modifier.padding(top = 2.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("I declare this credential is mine and in force"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    Text(L("Your declaration, the format check and the registry link make it verified. It is kept in the audit log."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        } else if (!editing) {
            Text(
                LF("%@ declares it is theirs from their own account; until then it shows as not declared.", memberName.nonEmpty ?: L("The professional")),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
        } else {
            Text(L("Changing the number, state or address asks for the declaration again."), style = Theme.text(12), color = Theme.inkSoft)
        }
        TeamLegalReviewNote()
    }
}
