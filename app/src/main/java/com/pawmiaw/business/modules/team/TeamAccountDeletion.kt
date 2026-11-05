package com.pawmiaw.business.team

import android.icu.text.ListFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

//
//  TeamAccountDeletion.kt (iOS TeamAccountDeletion.swift)
//
//  Deleting the account, start to finish, as the business workspace draws it
//  (web: components/app/account-deletion-panel.tsx, account-deletion-
//  households.ts; hooks/use-account-deletion.ts; backend
//  routes/account-deletion.ts): what it would remove, what happens to each
//  household another adult could keep and to each pet a Familia could take,
//  the word typed before the code is emailed, the code, the box ticked, and
//  scheduling it. A deletion already scheduled is said in the card's place,
//  with the way to cancel it. Once scheduled the person stays signed in, as
//  on the web, and sees the pending deletion with its Cancel.
//

// MARK: - Shapes

@Serializable
data class TeamDeletionHousehold(
    val id: String,
    val name: String? = null,
    val memberCount: Int? = null,
    val petCount: Int? = null,
    val recordCount: Int? = null,
    val fileCount: Int? = null,
    val members: List<Person>? = null,
    val successors: List<Successor>? = null,
) {
    @Serializable
    data class Person(val name: String? = null, val role: String? = null)

    @Serializable
    data class Successor(val userId: String, val name: String? = null)
}

@Serializable
data class TeamDeletionPetTransfer(
    val petId: String,
    val petName: String? = null,
    val candidates: List<Candidate>? = null,
) {
    @Serializable
    data class Candidate(val userId: String, val name: String? = null, val email: String? = null)
}

@Serializable
data class TeamDeletionImpact(
    val userId: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val ownedHouseholds: List<TeamDeletionHousehold>? = null,
    val joinedHouseholds: List<Named>? = null,
    val providerWorkspaces: List<Named>? = null,
    val hasAppleAccount: Boolean? = null,
    val requiresAppleReauthorization: Boolean? = null,
    val hasActiveAppleSubscription: Boolean? = null,
    val hasActiveWebSubscription: Boolean? = null,
    val gracePeriodHours: Int? = null,
    val petTransfers: List<TeamDeletionPetTransfer>? = null,
    val transferHoldEndsAt: String? = null,
) {
    @Serializable
    data class Named(val id: String? = null, val name: String? = null, val role: String? = null)
}

@Serializable
data class TeamDeletionStatus(
    /** none, pending, executing, completed, cancelled or failed. */
    val status: String? = null,
    val stage: String? = null,
    val requestedAt: String? = null,
    val scheduledFor: String? = null,
    val impact: Snapshot? = null,
    val handover: List<Handover>? = null,
) {
    @Serializable
    data class Snapshot(val ownedHouseholds: List<TeamDeletionHousehold>? = null)

    @Serializable
    data class Handover(val householdId: String, val householdName: String? = null, val toUserId: String? = null, val toName: String? = null)
}

// MARK: - Words

object TeamDeletionWords {
    /** The answer "delete it for everyone", beside the ids of the adults who could keep it. */
    const val deleteForEveryone = "delete-for-everyone"
    /** A pet not given to anyone closes with its household. */
    const val keepNoOne = "keep-no-one"

    /** The word typed before the code is emailed: "ELIMINAR", "DELETE". The server takes either. */
    val word: String get() = L("DELETE")

    /** A household's name as the reader sees it: the server's own English names read in Spanish. */
    fun household(name: String?): String {
        val name = name ?: ""
        if (LocalizationManager.currentLanguage != AppLanguage.spanish) return name
        val trimmed = name.trim()
        if (trimmed.lowercase() == "my household") return "Mi hogar"
        val match = Regex("(?i)(.+?)(?:['’]s|['’]) household").matchEntire(trimmed)
        if (match != null) {
            val owner = match.groupValues[1].trim()
            if (owner.isNotEmpty()) return "Hogar de $owner"
        }
        return name
    }

    /** "Carmen", "Carmen y Luis", "Ana, Luis y 3 personas más". */
    fun people(names: List<String>): String {
        val shown = if (names.size > 3) names.take(2) + LF("%d more people", names.size - 2) else names
        return runCatching { ListFormatter.getInstance(appLocale).format(shown) }.getOrNull() ?: shown.joinToString(", ")
    }

    /** Who loses a household that is deleted for everyone, by name. */
    fun accessLoss(household: TeamDeletionHousehold): String {
        val names = (household.members ?: emptyList()).mapNotNull { it.name.nonEmpty }
        if (names.isEmpty()) return L("Nobody else has access to this household.")
        return if (names.size == 1) LF("%1\$@ loses access to %2\$@.", people(names), household(household.name))
        else LF("%1\$@ lose access to %2\$@.", people(names), household(household.name))
    }

    /** What was decided for a household, in a line; null while it is still to be chosen. */
    fun outcome(household: TeamDeletionHousehold, answer: String?): String? {
        household.successors?.firstOrNull { it.userId == answer }?.let { successor ->
            return LF("%1\$@ goes to %2\$@", household(household.name), successor.name ?: "")
        }
        if (!(household.successors ?: emptyList()).isEmpty() && answer != deleteForEveryone) return null
        return LF("%@ is deleted for everyone", household(household.name))
    }

    /** What a scheduled deletion will do to each household its owner has. */
    fun scheduledOutcomes(status: TeamDeletionStatus?): List<String> {
        val handover = status?.handover ?: emptyList()
        return (status?.impact?.ownedHouseholds ?: emptyList()).map { household ->
            val plan = handover.firstOrNull { it.householdId == household.id }
            if (plan != null) LF("%1\$@ will go to %2\$@.", household(household.name), plan.toName ?: "")
            else LF("%@ will be deleted for everyone.", household(household.name))
        }
    }

    fun summary(graceHours: Int?): String {
        val first = L("Schedule permanent deletion of your account. The households you own are deleted with it, unless you leave one to another adult.")
        if (graceHours != null) {
            return "$first ${L("You have")} $graceHours ${L("hours to cancel from PawMiaw or the security email.")}"
        }
        return "$first ${L("You can cancel from PawMiaw or the security email during the cancellation window.")}"
    }

    /** iOS `DateFormatter` with `.long` date and `.short` time, in the app's language. */
    fun longDateTime(date: Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(appLocale).withZone(ZoneId.systemDefault()).format(date)

    fun scheduledSummary(scheduledFor: String?, executing: Boolean): String {
        if (executing) return L("Deletion has started and can no longer be cancelled.")
        val date = DateText.parse(scheduledFor)
        if (date != null) {
            return "${L("Your account remains frozen until permanent deletion on")} ${longDateTime(date)}."
        }
        return L("Your account is frozen during the cancellation period.")
    }

    fun counts(household: TeamDeletionHousehold): String = listOf(
        LP(household.petCount ?: 0, "%d pet", "%d pets"),
        LP(household.memberCount ?: 0, "%d member", "%d members"),
        LP(household.recordCount ?: 0, "%d record", "%d records"),
    ).joinToString(", ")
}

// MARK: - The panel

/** The business workspace's picture of deleting the account: a panel that opens its review. */
@Composable
fun TeamAccountDeletionPanel(modifier: Modifier = Modifier) {
    val session = SessionStore.shared
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TeamDeletionStatus?>(null) }
    var statusLoaded by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var impact by remember { mutableStateOf<TeamDeletionImpact?>(null) }
    var impactLoading by remember { mutableStateOf(false) }
    var impactFailed by remember { mutableStateOf(false) }
    var gateOpen by remember { mutableStateOf(false) }
    var codeSent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var householdAnswers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var householdErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var petAnswers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sending by remember { mutableStateOf(false) }
    var scheduling by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }

    val isPending = status?.status == "pending"
    val isExecuting = status?.status == "executing" || status?.status == "failed"
    val graceHours = impact?.gracePeriodHours
    val appleBlocks = impact?.requiresAppleReauthorization == true
    val canSubmit = code.length == 6 && codeSent && acknowledged && !appleBlocks

    // MARK: Actions

    suspend fun loadStatus() {
        status = runCatching { API.get<TeamDeletionStatus>("/users/me/account-deletion") }.getOrNull()
        statusLoaded = true
    }

    suspend fun loadImpact() {
        impactLoading = true
        try {
            impact = API.get("/users/me/account-deletion/impact")
            impactFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            impactFailed = impact == null
        } finally {
            impactLoading = false
        }
    }

    /** Every household another adult could keep needs its answer first; nothing is answered for the person. */
    fun reportUnanswered(): Boolean {
        val errors = LinkedHashMap<String, String>()
        for (household in impact?.ownedHouseholds ?: emptyList()) {
            if ((household.successors ?: emptyList()).isEmpty()) continue
            if (TeamDeletionWords.outcome(household, householdAnswers[household.id]) == null) {
                errors[household.id] = LF("Choose what happens to %@.", TeamDeletionWords.household(household.name))
            }
        }
        householdErrors = errors
        val first = errors.values.firstOrNull()
        if (first != null) {
            Toast.error(if (errors.size == 1) first else LF("Check the %d marked fields.", errors.size))
            return true
        }
        return false
    }

    /** The word is asked only once every household has its answer. */
    fun openGate() {
        if (reportUnanswered()) return
        gateOpen = true
    }

    suspend fun sendCode(first: Boolean) {
        sending = true
        try {
            API.send("POST", "/users/me/account-deletion/verification")
            if (first) codeSent = true
            Toast.show(if (first) L("A verification code was sent to your email.") else L("A new code was sent to your email."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = errorText(e, fallback = L("The code could not be sent."))
            if (first) throw APIError(status = (e as? APIError)?.status ?: 0, message = message, code = null, fields = emptyMap())
            Toast.error(message)
        } finally {
            sending = false
        }
    }

    /**
     * The adult chosen for a household can no longer take it: who can is read again and those
     * households are asked again, with the server's sentence under them. The code is not spent.
     */
    suspend fun handoverRefused(sentence: String, sent: Map<String, String>) {
        loadImpact()
        val fresh = impact?.ownedHouseholds ?: emptyList()
        val stale = sent.keys.filter { id ->
            !(fresh.firstOrNull { it.id == id }?.successors?.any { it.userId == sent[id] } ?: false)
        }
        val refused = if (stale.isEmpty()) sent.keys.toList() else stale
        householdAnswers = householdAnswers - refused.toSet()
        householdErrors = householdErrors + refused.associateWith { sentence }
        Toast.error(sentence)
    }

    fun schedule() {
        // Who can take a household over may have changed since the first step.
        if (reportUnanswered()) return
        val current = impact ?: return
        val handover = LinkedHashMap<String, String>()
        for (household in current.ownedHouseholds ?: emptyList()) {
            val answer = householdAnswers[household.id]
            if (answer != null && household.successors?.any { it.userId == answer } == true) handover[household.id] = answer
        }
        val given = LinkedHashMap<String, String>()
        for (pet in current.petTransfers ?: emptyList()) {
            val answer = petAnswers[pet.petId]
            if (answer != null && pet.candidates?.any { it.userId == answer } == true) given[pet.petId] = answer
        }
        val body = mutableMapOf<String, Any?>("verificationCode" to code, "confirmation" to TeamDeletionWords.word, "acknowledgeNoAutomaticRefund" to true)
        if (handover.isNotEmpty()) body["handover"] = handover
        if (given.isNotEmpty()) body["petTransfers"] = given
        scheduling = true
        scope.launch {
            try {
                API.post<EmptyResponse>("/users/me/account-deletion", body)
                Toast.show(L("Account deletion was scheduled. Check your email for the cancellation link."))
                // Like the web: still signed in, the card turns into the scheduled deletion and the way to cancel it.
                expanded = false
                codeSent = false
                code = ""
                acknowledged = false
                impact = null
                loadStatus()
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                if (e.code == "HANDOVER_NOT_ALLOWED") handoverRefused(e.message, handover)
                else Toast.error(errorText(e, fallback = L("Account deletion could not be scheduled.")))
            } catch (e: Exception) {
                Toast.error(errorText(e, fallback = L("Account deletion could not be scheduled.")))
            } finally {
                scheduling = false
            }
        }
    }

    fun cancelDeletion() {
        cancelling = true
        scope.launch {
            try {
                status = API.post("/users/me/account-deletion/cancel")
                Toast.show(L("Account deletion was cancelled."))
                expanded = false
                codeSent = false
                code = ""
                acknowledged = false
                impact = null
                loadStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(errorText(e, fallback = L("Deletion could not be cancelled.")))
            } finally {
                cancelling = false
            }
        }
    }

    /** A pet given during the password hold moves the deletion to the hold's end. */
    fun holdLines(impact: TeamDeletionImpact): List<String> {
        val hold = DateText.parse(impact.transferHoldEndsAt) ?: return emptyList()
        val usual = Instant.now().plusSeconds(((impact.gracePeriodHours ?: 0) * 3600).toLong())
        if (hold <= usual) return emptyList()
        return (impact.petTransfers ?: emptyList()).mapNotNull { pet ->
            val person = pet.candidates?.firstOrNull { it.userId == petAnswers[pet.petId] } ?: return@mapNotNull null
            LF("Your account will be deleted on %1\$@, when %2\$@ can go to %3\$@.", TeamDeletionWords.longDateTime(hold), pet.petName ?: "", person.name ?: "")
        }
    }

    LaunchedEffect(Unit) { loadStatus() }

    // MARK: Pieces

    /** What happens to one household the person owns: theirs to decide when another adult has full access. */
    @Composable
    fun Fate(household: TeamDeletionHousehold) {
        val name = TeamDeletionWords.household(household.name)
        val successors = household.successors ?: emptyList()
        if (successors.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel(LF("What happens to %@?", name), caps = true, required = true)
                ChoiceCards(
                    options = successors.map { person ->
                        Triple(person.userId, LF("Leave %1\$@ to %2\$@", name, person.name ?: ""), LF("%@ becomes its owner. The household keeps its pets, records and members, and moves to the free plan: Premium is yours and does not transfer.", person.name ?: "") as String?)
                    } + Triple(TeamDeletionWords.deleteForEveryone, LF("Delete %@ for everyone", name), TeamDeletionWords.accessLoss(household)),
                    // `.disabled(scheduling)`: the answer stays while it schedules.
                    selection = stateOf({ householdAnswers[household.id] ?: "" }, { value ->
                        if (!scheduling) {
                            householdAnswers = householdAnswers + (household.id to value)
                            householdErrors = householdErrors - household.id
                        }
                    }),
                )
                householdErrors[household.id]?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(LF("%@ is deleted for everyone.", name) + " " + TeamDeletionWords.accessLoss(household), style = Theme.text(14), color = Theme.inkSoft)
                if (!(household.members ?: emptyList()).isEmpty()) {
                    Text(L("To leave it to someone, that person needs full access to the household."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
    }

    /** "Dar a Luna a Tomás": who gets each pet a Familia could keep, or nobody. */
    @Composable
    fun PetTransfers(impact: TeamDeletionImpact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RowDivider()
            Text(L("Give a pet to someone who will keep it"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(L("They are told at once and have until the deletion runs to accept. A pet nobody accepts closes with the household."), style = Theme.text(12), color = Theme.inkSoft)
            (impact.petTransfers ?: emptyList()).forEach { pet ->
                val petName = pet.petName ?: L("a pet")
                Text(LF("What happens to %@?", petName), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                ChoiceCards(
                    options = (pet.candidates ?: emptyList()).map { person ->
                        Triple(person.userId, LF("Give %1\$@ to %2\$@", petName, person.name ?: ""), LF("%1\$@ moves to %2\$@’s family with all of the history, once %3\$@ accepts.", petName, person.name ?: "", person.email ?: "") as String?)
                    } + Triple(TeamDeletionWords.keepNoOne, LF("Do not give %@", petName), LF("%@ closes with the household.", petName)),
                    selection = stateOf({ petAnswers[pet.petId] ?: "" }, { value -> if (!scheduling) petAnswers = petAnswers + (pet.petId to value) }),
                )
            }
            holdLines(impact).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Glyph("clock-3", 16.dp, tint = Theme.coral, modifier = Modifier.padding(top = 2.dp))
                    Text(line, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                }
            }
        }
    }

    @Composable
    fun Boundary(impact: TeamDeletionImpact) {
        val owned = impact.ownedHouseholds ?: emptyList()
        val chip = RoundedCornerShape(Theme.chipRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.surface, chip)
                .border(2.dp, Theme.coral, chip)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("triangle-alert", 16.dp, tint = Theme.coral)
                Text(L("This is the permanent deletion boundary"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
            }
            if (owned.isEmpty()) {
                Text(L("You do not own a household."), style = Theme.text(14), color = Theme.inkSoft)
            } else {
                owned.forEach { household ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val strong = Theme.inkStrong
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append(TeamDeletionWords.household(household.name)) }
                                append(" — " + TeamDeletionWords.counts(household))
                            },
                            style = Theme.text(14),
                            color = Theme.inkSoft,
                        )
                        Fate(household)
                    }
                }
            }
            if (!(impact.petTransfers ?: emptyList()).isEmpty()) PetTransfers(impact)
            if (!(impact.joinedHouseholds ?: emptyList()).isEmpty()) {
                TeamIconLine(icon = "users", tint = Theme.blue, text = L("Households you only joined will remain. Your membership and personal content will be removed."))
            }
            if (!(impact.providerWorkspaces ?: emptyList()).isEmpty()) {
                TeamIconLine(icon = "users", tint = Theme.blue, text = L("Provider workspace records remain business-owned. Your personal profile and staff membership will be removed."))
            }
            TeamIconLine(
                icon = "clock-3",
                tint = Theme.coral,
                text = "${L("Permanent deletion begins after")} ${impact.gracePeriodHours ?: 0} ${L("hours. Until then, your account is frozen and you can cancel from the app or the security email.")}",
            )
        }
    }

    @Composable
    fun CodeStep(impact: TeamDeletionImpact) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Disabled(scheduling) {
                PMTextField(
                    L("Six-digit email code"),
                    text = stateOf({ code }, { code = it.filter { c -> c.isDigit() }.take(6) }),
                    keyboard = KeyboardType.Number,
                )
            }
            Row(
                Modifier.plainButton(enabled = !(sending || scheduling)) { scope.launch { runCatching { sendCode(first = false) } } },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("rotate-cw", 14.dp, tint = Theme.blue)
                Text(L("Resend code"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
            }
            holdLines(impact).forEach { line ->
                Text(line, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            }
            (impact.ownedHouseholds ?: emptyList()).mapNotNull { TeamDeletionWords.outcome(it, householdAnswers[it.id]) }.forEach { line ->
                Text(line, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            }
            val done = ProviderState.done.color
            Row(
                Modifier
                    .background(Theme.wash(done, 18.0), CapsuleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("circle-check", 16.dp, tint = done)
                Text(LF("%@ confirmed", TeamDeletionWords.word), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainButton(enabled = !scheduling) { acknowledged = !acknowledged },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Tick(on = acknowledged, modifier = Modifier.padding(top = 2.dp))
                Text(
                    L("I understand deletion is permanent after it begins and PawMiaw does not issue an automatic or prorated subscription refund."),
                    Modifier.weight(1f),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
            FlowLayout(spacing = 10.dp) {
                PMButton(L("Schedule permanent deletion"), systemImage = "trash-2", variant = ButtonVariant.destructive, isWorking = scheduling, enabled = canSubmit && !scheduling) { schedule() }
                Text(
                    "${impact.gracePeriodHours ?: 0} ${L("hours to cancel")}",
                    Modifier
                        .background(Theme.surface, CapsuleShape)
                        .border(1.dp, Theme.borderSoft, CapsuleShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = Theme.text(14, FontWeight.SemiBold),
                    color = Theme.inkStrong,
                )
            }
        }
    }

    @Composable
    fun Steps() {
        val loadedImpact = impact
        if (impactLoading && loadedImpact == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                Text(L("Calculating everything affected by deletion…"), style = Theme.text(14), color = Theme.inkSoft)
            }
        } else if (loadedImpact != null) {
            Boundary(loadedImpact)
            val chip = RoundedCornerShape(Theme.chipRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surface, chip)
                    .border(1.dp, Theme.borderSoft, chip)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(L("Paid access is stopped according to the store or billing provider. Deleting your account does not create an automatic or prorated refund."), style = Theme.text(14), color = Theme.inkSoft)
                if (loadedImpact.hasActiveAppleSubscription == true) {
                    Text(L("Apple subscriptions remain managed in App Store Subscriptions and may need to be cancelled there."), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
            if (appleBlocks) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.surface, chip)
                        .border(2.dp, Theme.borderSoft, chip)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(L("Sign in with Apple again so PawMiaw can revoke your Apple authorization when deletion completes."), style = Theme.text(14), color = Theme.inkSoft)
                    // Not ported: Sign in with Apple isn't set up in this app; the web asks for it and finishes the deletion there.
                    TeamWebRow(title = L("Continue with Apple"), detail = L("Opens on the web."), systemImage = "apple.logo", url = TeamWeb.url("provider/account/security"))
                }
            }
            if (!codeSent) {
                PMButton(L("Email verification code"), systemImage = "mail", variant = ButtonVariant.destructive, isWorking = sending, enabled = !(sending || appleBlocks)) { openGate() }
            } else {
                CodeStep(loadedImpact)
            }
        } else if (impactFailed) {
            Text(L("Deletion impact could not be loaded."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive)
            PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { loadImpact() } }
        }
    }

    // MARK: Body

    val cardShape = RoundedCornerShape(Theme.cardRadius)
    if (!statusLoaded) {
        TeamPanel(title = L("Delete account"), systemImage = "trash-2", tint = Theme.coral, modifier = modifier) {
            TeamProgress(minHeight = 100.dp)
        }
    } else if (isPending || isExecuting) {
        // Scheduled.
        val outcomes = TeamDeletionWords.scheduledOutcomes(status)
        TeamPanel(
            title = if (isExecuting) L("Permanent deletion is in progress") else L("Account deletion scheduled"),
            subtitle = TeamDeletionWords.scheduledSummary(status?.scheduledFor, executing = isExecuting),
            systemImage = "clock-3",
            tint = Theme.coral,
            modifier = modifier.border(2.dp, Theme.coral, cardShape),
        ) {
            if (!isExecuting) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    outcomes.forEach { line -> Text(line, style = Theme.text(14), color = Theme.inkSoft) }
                }
                PMButton(L("Cancel account deletion"), isWorking = cancelling) { cancelDeletion() }
            }
        }
    } else {
        // Not scheduled.
        TeamPanel(
            title = L("Delete account"),
            subtitle = TeamDeletionWords.summary(graceHours),
            systemImage = "trash-2",
            tint = Theme.coral,
            modifier = modifier.border(2.dp, Theme.mix(Theme.coral, 58.0, Theme.borderSoft), cardShape),
        ) {
            if (expanded) {
                Text(
                    (if (codeSent) L("Step 2 of 2 · Confirm with the code we emailed you") else L("Step 1 of 2 · Review what permanent deletion removes")).uppercase(appLocale),
                    style = Theme.text(12, FontWeight.Bold).tracking(0.6),
                    color = Theme.destructive,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { Steps() }
            } else {
                PMButton(L("Review account deletion"), systemImage = "trash-2", variant = ButtonVariant.destructive) {
                    expanded = true
                    scope.launch { loadImpact() }
                }
            }
        }
    }

    if (gateOpen) {
        AppSheet(onDismissRequest = { gateOpen = false }) {
            // The typed word that stands before the code is emailed (web `AccountDeletionSendGate`):
            // the coral band with the bin, where the code goes and the time to cancel, the word to type.
            val email = impact?.email ?: session.user?.email ?: ""
            ConfirmSheet(
                title = L("Confirm before we email the code"),
                message = LF("Type %@ to receive the deletion code. Nothing is deleted yet — you still confirm on the next step.", TeamDeletionWords.word),
                confirmTitle = L("Email verification code"),
                destructive = true,
                accent = ModalAccent.coral,
                icon = "trash-2",
                confirmWord = TeamDeletionWords.word,
                confirmHint = listOfNotNull(
                    email.nonEmpty?.let { "${L("Code goes to")}: $it" },
                    graceHours?.let { "${L("Time to cancel")}: $it ${L("hours")}" },
                ).joinToString(" · ").nonEmpty,
            ) { _ ->
                sendCode(first = true)
            }
        }
    }
}

/** A line with a small coloured icon before it (the deletion review's facts). */
@Composable
private fun TeamIconLine(icon: String, tint: Color = Theme.blue, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Glyph(icon, 16.dp, tint = tint, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = Theme.text(14), color = Theme.inkSoft)
    }
}
