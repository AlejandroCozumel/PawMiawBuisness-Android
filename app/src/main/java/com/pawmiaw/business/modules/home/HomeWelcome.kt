package com.pawmiaw.business.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import com.pawmiaw.business.team.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.ZoneId
import kotlin.math.max

//
//  HomeWelcome.kt (iOS HomeWelcome.swift)
//
//  The welcome for someone invited to the workspace (web
//  member-onboarding/, backend docs/member-onboarding.md): a hello from the
//  business and who invited them, their steps by role — you (name as it
//  prints, photo, phone), language and alerts, where and how you work, the
//  signature adopted once, the credentials — and a done page that says what
//  was left for later. Never a wall: "Más tarde" closes it and Home offers it
//  back as reminders. Server: `/provider-member-onboarding`.
//

// MARK: - The state (GET /provider-member-onboarding)

@Serializable
data class HomeWelcomeState(
    val applicable: Boolean? = null,
    val show: Boolean? = null,
    val status: String? = null,
    val joinedBefore: Boolean? = null,
    val steps: List<Step>? = null,
    val currentStep: String? = null,
    val welcome: Welcome? = null,
    val profile: Profile? = null,
    val preferences: Preferences? = null,
    val work: Work? = null,
    val signature: Signature? = null,
    val credentials: Credentials? = null,
    val reminders: List<HomeMemberReminder>? = null,
) {
    @Serializable
    data class Step(val id: String, val done: Boolean? = null, val skipped: Boolean? = null)

    @Serializable
    data class Welcome(
        val businessName: String? = null,
        val logoUrl: String? = null,
        val categories: List<String>? = null,
        val country: String? = null,
        val invitedByName: String? = null,
        val role: String? = null,
    )

    @Serializable
    data class Profile(
        val displayName: String? = null,
        val accountName: String? = null,
        val professionalTitle: String? = null,
        val email: String? = null,
        val phone: String? = null,
        val photoUrl: String? = null,
    )

    @Serializable
    data class Preferences(val language: String? = null, val languageChosen: Boolean? = null)

    @Serializable
    data class Place(val id: String, val name: String? = null, val isPrimary: Boolean? = null)

    @Serializable
    data class Work(
        val role: String? = null,
        val permissions: List<String>? = null,
        val locations: List<Place>? = null,
        val everyLocation: Boolean? = null,
        val locationCount: Int? = null,
    )

    @Serializable
    data class Adoption(val termsVersion: String? = null, val acceptedAt: String? = null, val current: Boolean? = null)

    @Serializable
    data class Signature(
        val needed: Boolean? = null,
        val saved: SignatureMark? = null,
        val adoption: Adoption? = null,
        val adopted: Boolean? = null,
    )

    @Serializable
    data class Credential(
        val id: String,
        val type: String,
        val label: Label? = null,
        val number: String? = null,
        val region: String? = null,
        val issuer: String? = null,
        val title: String? = null,
        val expiresOn: String? = null,
        val status: String? = null,
        val verified: Boolean? = null,
    ) {
        @Serializable
        data class Label(val es: String? = null, val en: String? = null)
    }

    @Serializable
    data class Credentials(
        val kind: String? = null,
        val country: String? = null,
        val required: String? = null,
        val offered: List<String>? = null,
        val businessAddress: String? = null,
        val canPrescribe: Boolean? = null,
        val items: List<Credential>? = null,
    )

    val stepIds: List<String> get() = (steps ?: emptyList()).map { it.id }
    val businessName: String get() = welcome?.businessName ?: Workspace.shared.current?.name ?: "PawMiaw"

    /** Hi to the person, not to their title: the account's first name. */
    val firstName: String
        get() {
            val name = profile?.accountName.nonEmpty ?: profile?.displayName ?: ""
            return name.split(" ").firstOrNull { it.isNotEmpty() } ?: ""
        }

    companion object {
        /** The name a document prints: what they typed, else the account's. */
        fun printedName(typed: String, account: String?): String {
            val clean = typed.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            return if (clean.isEmpty()) (account ?: "").trim() else clean
        }
    }
}

/** The welcome's words and rules (web lib/member-onboarding.ts). */
object HomeWelcomeText {
    fun stepLabel(step: String): String = when (step) {
        "profile" -> L("You")
        "preferences" -> L("Alerts")
        "work" -> L("Your role")
        "signature" -> L("Signature")
        else -> L("Credentials")
    }

    fun stepPromise(step: String): String = when (step) {
        "profile" -> L("Your name as it prints, a photo and your phone")
        "preferences" -> L("Your language and which alerts reach you")
        "work" -> L("Your role, what it lets you do and where")
        "signature" -> L("Your signature, adopted once: then signing is one tap")
        else -> L("Your license and certifications")
    }

    /** The web's `STEP_ICON` (Lucide). */
    fun stepIcon(step: String): String = when (step) {
        "profile" -> "user-round"
        "preferences" -> "bell-ring"
        "work" -> "map-pin"
        "signature" -> "file-pen-line"
        else -> "id-card"
    }

    /** The role said for the business: a groomer business's support staff are its groomers. */
    fun roleLabel(role: String?, categories: List<String>): String {
        when (role) {
            "owner" -> return L("Owner")
            "admin" -> return L("Admin")
            "receptionist" -> return L("Front desk")
        }
        val clinic = categories.contains("vet") || categories.contains("emergency")
        if (clinic) return if (role == "vet") L("Vet") else L("Vet tech / assistant")
        if (categories.contains("groomer")) return L("Groomer")
        if (categories.contains("walker")) return L("Walker")
        if (categories.contains("trainer")) return L("Trainer")
        if (categories.contains("sitter")) return L("Sitter")
        if (categories.contains("boarding") || categories.contains("daycare")) return L("Caregiver")
        return if (role == "vet") L("Vet") else L("Support staff")
    }

    fun titlePrompt(role: String?, categories: List<String>, country: String?): String {
        val clinic = categories.contains("vet") || categories.contains("emergency")
        if (clinic && role == "vet") return if (country == "MX") L("e.g. Médico Veterinario Zootecnista") else L("e.g. DVM")
        if (clinic && role == "support_staff") return if (country == "US") L("e.g. Registered Veterinary Technician") else L("e.g. Vet assistant")
        if (categories.contains("groomer")) return L("e.g. Certified groomer")
        return L("e.g. Front desk")
    }

    /** What someone with these permissions can do, one plain line each: (icon, text). */
    fun abilities(permissions: List<String>): List<Pair<String, String>> {
        fun has(p: String): Boolean = permissions.contains(p)
        val lines = mutableListOf<Pair<String, String>>()
        if (has("appointments.create") || has("appointments.view")) {
            lines.add("calendar" to (if (has("appointments.assign_anyone")) L("See and book visits for the whole team") else if (has("appointments.create")) L("See and book your visits") else L("See the day’s visits")))
        }
        if (has("appointments.check_in") || has("appointments.complete")) {
            lines.add("stethoscope" to (if (has("appointments.complete")) L("Check pets in, start and finish visits") else L("Check pets in when they arrive")))
        }
        if (has("records.create")) lines.add("note.text" to (if (has("records.approve")) L("Write clinical notes and records") else L("Write notes for the vet to sign")))
        if (has("records.approve")) lines.add("signature" to L("Sign records, prescriptions and discharges"))
        if (has("clients.view")) lines.add("pawprint.fill" to (if (has("clients.assign_anyone")) L("See every client and their pets") else L("See your clients and their pets")))
        if (has("billing.record_payment")) lines.add("creditcard.fill" to (if (has("billing.apply_discount")) L("Charge visits, take payments and give discounts") else L("Charge visits and take payments")))
        if (has("sales.sell")) lines.add("bag.fill" to (if (has("sales.refund")) L("Sell at the counter and take returns") else L("Sell at the counter")))
        if (has("stays.care") || has("stays.manage")) lines.add("bed.double.fill" to (if (has("stays.manage")) L("Book stays and care for the pets staying in") else L("Care for the pets staying in")))
        if (has("hospital.care") || has("hospital.manage")) lines.add("cross.case.fill" to (if (has("hospital.manage")) L("Admit, treat and discharge hospital patients") else L("Give treatments and record vitals in the hospital")))
        if (has("inventory.adjust") || has("inventory.receive")) lines.add("shippingbox.fill" to L("Receive and count stock"))
        if (has("staff.invite") || has("staff.edit_roles")) lines.add("person.3.fill" to L("Invite people and set what they can do"))
        if (has("workspace.edit")) lines.add("gearshape.fill" to L("Change the workspace settings"))
        return lines
    }

    /** What stays with someone else — so nobody looks for a button that isn't theirs. */
    fun handledByOthers(permissions: List<String>): List<String> {
        fun has(p: String): Boolean = permissions.contains(p)
        val lines = mutableListOf<String>()
        if (!has("records.approve") && has("records.create")) lines.add(L("Signing records and prescriptions (a vet does)"))
        if (!has("billing.record_payment") && !has("sales.sell")) lines.add(L("Taking payments"))
        if (!has("sales.refund") && has("sales.sell")) lines.add(L("Refunds"))
        if (!has("staff.invite")) lines.add(L("Inviting people"))
        if (!has("billing.manage")) lines.add(L("The plan and billing"))
        return lines
    }

    fun credentialNoun(type: String?): String = when (type) {
        "cedula_profesional" -> L("your cédula profesional")
        "state_license" -> L("your state license")
        else -> L("your professional license")
    }

    fun optionalWhy(type: String): String? = when (type) {
        "dea" -> L("Only to prescribe controlled substances.")
        "state_controlled" -> L("Some states ask for it, besides the DEA, for controlled substances.")
        "usda_accreditation" -> L("For official health certificates.")
        "vet_tech_license" -> L("Your RVT, LVT or CVT and the state.")
        "certification" -> L("Courses and certifications families trust. Shown on your profile.")
        "other_license" -> L("The license your country issues to practise.")
        else -> null
    }

    /** The categories of alerts this person's role can see (security alerts always reach them). */
    fun alertCategories(permissions: List<String>): List<String> {
        val needs = listOf(
            "appointments" to "notifications.appointments",
            "provider_follow_ups" to "notifications.provider_followups",
            "records_documentation" to "notifications.records_documentation",
            "billing" to "notifications.billing",
            "client_messages" to "notifications.client_messages",
        )
        return needs.filter { permissions.contains(it.second) }.map { it.first }
    }

    /** (title, body). */
    fun alertCopy(category: String): Pair<String, String> = when (category) {
        "appointments" -> L("Visits@alerts") to L("New bookings, arrivals, changes and no-shows.")
        "provider_follow_ups" -> L("Follow-ups") to L("Callbacks, rechecks and lab results to review.")
        "records_documentation" -> L("Records@alerts") to L("Notes waiting for a signature and missing paperwork.")
        "billing" -> L("Money@alerts") to L("Bills to send and payments due.")
        else -> L("Messages@alerts") to L("Families writing to the business.")
    }
}

// MARK: - The welcome

private enum class HomeWelcomeStage { hello, steps, done }

/**
 * The welcome, over the whole app (`HomeFullScreenCover`): its own bar with the business and
 * "Más tarde", then the hello, a step, or the done page, on the canvas.
 */
@Composable
fun HomeWelcomeScreen(state: HomeWelcomeState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val current = remember { mutableStateOf(state) }
    var stage by remember {
        mutableStateOf(if (state.status == "new") HomeWelcomeStage.hello else (if (state.status == "completed") HomeWelcomeStage.done else HomeWelcomeStage.steps))
    }
    var step by remember { mutableStateOf(state.currentStep ?: state.stepIds.firstOrNull() ?: "profile") }
    var leaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val welcome = current.value

    fun nextStep(after: String): String? {
        val ids = current.value.stepIds
        val index = ids.indexOf(after)
        if (index < 0 || index + 1 >= ids.size) return null
        return ids[index + 1]
    }

    fun back(from: String) {
        val ids = current.value.stepIds
        val index = ids.indexOf(from)
        if (index > 0) step = ids[index - 1] else stage = HomeWelcomeStage.hello
    }

    /** Marks the step (the profile's save marks it itself), then the next one, or the done page. */
    suspend fun advanceAsync(from: String, action: String) {
        try {
            if (action == "skip" || from != "profile") {
                current.value = API.post("/provider-member-onboarding/steps/$from", mapOf("action" to action))
            }
            val next = nextStep(from)
            if (next != null) {
                step = next
                return
            }
            current.value = API.post("/provider-member-onboarding/finish", emptyMap())
            stage = HomeWelcomeStage.done
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(errorText(e, L("Could not save this step.")))
        }
    }

    fun advance(from: String, action: String) {
        scope.launch { advanceAsync(from, action) }
    }

    suspend fun reload() {
        val fresh = runCatching { API.get<HomeWelcomeState>("/provider-member-onboarding") }.getOrNull()
        if (fresh != null && fresh.applicable == true) current.value = fresh
    }

    fun later() {
        leaving = true
        scope.launch {
            try {
                API.post<EmptyResponse>("/provider-member-onboarding/dismiss", emptyMap())
                announceDataChanged()
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(errorText(e, L("Could not save. Try again.")))
            } finally {
                leaving = false
            }
        }
    }

    // Every step starts at the top, not where the last one ended.
    LaunchedEffect(step, stage) { scroll.animateScrollTo(0) }

    Column(modifier.fillMaxSize().background(Theme.canvas).statusBarsPadding()) {
        HomeWelcomeTopBar(state = welcome, showsLater = stage != HomeWelcomeStage.done, leaving = leaving, onLater = { later() })
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(scroll)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // The scroll's "top" anchor (iOS `Color.clear.frame(height: 0).id("top")`).
                Spacer(Modifier.height(0.dp))
                when (stage) {
                    HomeWelcomeStage.hello -> HomeWelcomeHello(state = welcome) {
                        step = welcome.currentStep ?: welcome.stepIds.firstOrNull() ?: "profile"
                        stage = HomeWelcomeStage.steps
                    }
                    HomeWelcomeStage.done -> HomeWelcomeDone(state = welcome, onClose = onClose)
                    HomeWelcomeStage.steps -> {
                        HomeWelcomeProgress(state = welcome, step = step)
                        when (step) {
                            "profile" -> HomeWelcomeProfileStep(
                                state = welcome,
                                onSaved = { current.value = it },
                                onDone = { advance("profile", "done") },
                                onSkip = { advance("profile", "skip") },
                            )
                            "preferences" -> HomeWelcomePreferencesStep(
                                state = welcome,
                                onDone = { advanceAsync("preferences", "done") },
                                onBack = { back("preferences") },
                                onSkip = { advance("preferences", "skip") },
                            )
                            "work" -> HomeWelcomeWorkStep(
                                state = welcome,
                                onDone = { advanceAsync("work", "done") },
                                onBack = { back("work") },
                            )
                            "signature" -> HomeWelcomeSignatureStep(
                                state = welcome,
                                onDone = { advanceAsync("signature", "done") },
                                onBack = { back("signature") },
                                onSkip = { advance("signature", "skip") },
                            )
                            else -> HomeWelcomeCredentialsStep(
                                state = welcome,
                                last = nextStep("credentials") == null,
                                onReload = { reload() },
                                onDone = { advanceAsync("credentials", "done") },
                                onBack = { back("credentials") },
                                onSkip = { advance("credentials", "skip") },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The business it is for, and "Más tarde" (web: an inline line on the page, a ghost button). */
@Composable
private fun HomeWelcomeTopBar(state: HomeWelcomeState, showsLater: Boolean, leaving: Boolean, onLater: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeBusinessMark(name = state.businessName, logoURL = state.welcome?.logoUrl, size = 32.dp)
        Text(
            state.businessName, Modifier.weight(1f), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (showsLater) {
            if (leaving) {
                HomeSpinner(modifier = Modifier.padding(horizontal = 12.dp))
            } else {
                PMButton(L("Later"), variant = ButtonVariant.link, size = ButtonSize.slim, tint = Theme.inkStrong) { onLater() }
            }
        }
    }
}

// MARK: Hello

@Composable
private fun HomeWelcomeHello(state: HomeWelcomeState, onStart: () -> Unit) {
    val categories = state.welcome?.categories ?: emptyList()
    val label = HomeWelcomeText.roleLabel(state.welcome?.role, categories)
    // Mid-sentence in Spanish the role reads in lower case ("como veterinario").
    val role = if (LocalizationManager.currentLanguage == AppLanguage.spanish) label.take(1).lowercase() + label.drop(1) else label
    val minutes = if (state.stepIds.contains("signature") || state.stepIds.contains("credentials")) 3 else 1
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        HomeBusinessMark(name = state.businessName, logoURL = state.welcome?.logoUrl, size = 96.dp)
        Text(
            (if (state.joinedBefore == true) L("New in your workspace") else L("Welcome to the team")).uppercase(),
            style = brandText(12, FontWeight.Bold).tracking(1.68), color = Theme.blue,
        )
        Text(
            if (state.firstName.isEmpty()) L("Hi!") else LF("Hi, %@!", state.firstName),
            style = brandHeading(38), color = Theme.inkStrong,
        )
        val invitedBy = state.welcome?.invitedByName
        Text(
            if (state.joinedBefore == true) LF("%@ now has a short welcome for its team. Finish your profile once and everything you sign carries it.", state.businessName)
            else if (invitedBy != null) LF("%@ invited you to %@ as %@.", invitedBy, state.businessName, role)
            else LF("You joined %@ as %@.", state.businessName, role),
            style = Theme.text(18), color = Theme.inkSoft,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PMButton(if (state.status == "new") L("Let’s start") else L("Pick up where I left off"), systemImage = "arrow.right") { onStart() }
            HomeWelcomeGlyphLabel(
                if (minutes <= 1) L("Under a minute") else LF("About %d minutes", minutes), icon = "clock",
                style = Theme.smallFont, color = Theme.inkSoft,
            )
        }
        Card {
            (state.steps ?: emptyList()).forEachIndexed { index, entry ->
                key(entry.id) {
                    if (index > 0) StitchedDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        if (entry.done == true) {
                            HomeDisc(icon = "check", size = 40.dp, iconSize = 20.dp, fill = Theme.blueFixed, ink = Color.White)
                        } else {
                            IconBadge(icon = HomeWelcomeText.stepIcon(entry.id), accent = Theme.blue, size = 40.dp, iconSize = 20.dp, ink = IconBadge.Ink.accent, washPercent = 10.0)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${index + 1}. ${HomeWelcomeText.stepPromise(entry.id)}", style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                            if (entry.id == "signature") Text(L("Because your role signs records and prescriptions."), style = Theme.captionFont, color = Theme.inkSoft)
                            if (entry.id == "credentials") Text(L("All optional, except what the law asks to prescribe."), style = Theme.captionFont, color = Theme.inkSoft)
                        }
                    }
                }
            }
        }
    }
}

// MARK: Done

@Composable
private fun HomeWelcomeDone(state: HomeWelcomeState, onClose: () -> Unit) {
    val waiting = (state.reminders ?: emptyList()).filter { it.id != "finish" }
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon = "party-popper", accent = Theme.lime, size = 80.dp, iconSize = 36.dp, ink = IconBadge.Ink.strong, washPercent = 45.0)
        Text(
            if (state.firstName.isEmpty()) L("All set!") else LF("All set, %@!", state.firstName),
            style = brandHeading(34), color = Theme.inkStrong, textAlign = TextAlign.Center,
        )
        Text(LF("Your profile at %@ is ready.", state.businessName), style = Theme.text(17), color = Theme.inkSoft, textAlign = TextAlign.Center)
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in state.steps ?: emptyList()) {
                key(entry.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Theme.surface, box)
                            .border(1.dp, Theme.borderSoft, box)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (entry.done == true) {
                            HomeDisc(icon = "check", size = 28.dp, iconSize = 16.dp, fill = Theme.blueFixed, ink = Color.White)
                        } else {
                            Box(Modifier.size(28.dp).dashedBorder(2.dp, Theme.borderSoft, on = 4.dp, off = 3.dp), contentAlignment = Alignment.Center) {
                                Glyph("clock", 14.dp, tint = Theme.inkSoft)
                            }
                        }
                        Text(HomeWelcomeText.stepPromise(entry.id), Modifier.weight(1f), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                        if (entry.done != true) Text(L("Later"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                    }
                }
            }
        }
        if (waiting.isNotEmpty()) {
            Text(
                L("What you left for later waits on your Home. Nothing is blocked: only signing asks for it, right there."),
                Modifier.widthIn(max = 460.dp), style = Theme.smallFont, color = Theme.inkSoft, textAlign = TextAlign.Center,
            )
        }
        PMButton(L("Go to my Home"), systemImage = "arrow.right") { onClose() }
    }
}

/** The business the person joined: its logo, or its initials (web `BusinessMark`: a tile, 32 / 48 / 96, chip / box / card corners). */
@Composable
fun HomeBusinessMark(name: String, logoURL: String?, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val radius = if (size >= 96.dp) Theme.cardRadius else (if (size >= 48.dp) Theme.boxRadius else Theme.chipRadius)
    val initials = name.split(" ").filter { it.isNotEmpty() }.take(2).mapNotNull { it.firstOrNull()?.toString()?.uppercase() }.joinToString("")
    val shape = RoundedCornerShape(radius)
    val url = APIConfig.assetURL(logoURL)
    Box(
        modifier
            .size(size)
            .cardShadow(shape)
            .clip(shape)
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize().padding(4.dp))
        } else {
            Text(
                initials.ifEmpty { "P" },
                style = brandHeading(if (size >= 96.dp) 30 else (if (size >= 48.dp) 16 else 12)), color = Theme.blue,
            )
        }
    }
}

/** One pip per step the person gets, done ones ticked (web `MemberProgress`). */
@Composable
private fun HomeWelcomeProgress(state: HomeWelcomeState, step: String) {
    val steps = state.steps ?: emptyList()
    val active = steps.indexOfFirst { it.id == step }.let { if (it < 0) 0 else it }
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = LF("Step %d of %d", active + 1, steps.size) },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        steps.forEachIndexed { index, entry ->
            key(entry.id) {
                val current = index == active
                val done = entry.done == true && !current
                val fill = if (current || done) Theme.blueFixed else (if (entry.skipped == true) Color.Transparent else Theme.surfaceMuted)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(fill, CircleShape)
                            .then(if (entry.skipped == true && !done && !current) Modifier.dashedBorder(2.dp, Theme.borderSoft, on = 4.dp, off = 3.dp) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) {
                            Glyph("check", 16.dp, tint = Color.White)
                        } else {
                            Text("${index + 1}", style = Theme.text(12, FontWeight.Bold), color = if (current) Color.White else Theme.inkSoft)
                        }
                    }
                    FittedText(
                        HomeWelcomeText.stepLabel(entry.id),
                        style = Theme.text(11.2, FontWeight.Bold), color = if (current) Theme.inkStrong else Theme.inkSoft,
                        minimumScaleFactor = 0.8f,
                    )
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (index <= active || entry.done == true) Theme.blue else Theme.borderSoft)
                    )
                }
            }
        }
    }
}

/**
 * A step's frame: "Paso N de M", its title and why, the questions, and Back / For later / Next.
 * `aside` follows the questions (the Swift's second view builder).
 */
@Composable
fun HomeWelcomeStepFrame(
    state: HomeWelcomeState,
    step: String,
    title: String,
    description: String,
    nextLabel: String,
    busy: Boolean = false,
    nextDisabled: Boolean = false,
    note: String? = null,
    onNext: () -> Unit,
    onBack: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
    aside: @Composable ColumnScope.() -> Unit,
) {
    val ids = state.stepIds
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                LF("Step %d of %d", ids.indexOf(step).let { if (it < 0) 0 else it } + 1, ids.size).uppercase(),
                style = brandText(12, FontWeight.Bold).tracking(1.68), color = Theme.blue,
            )
            Text(title, style = brandHeading(30), color = Theme.inkStrong)
            Text(description, style = Theme.text(16), color = Theme.inkSoft)
        }
        content()
        aside()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                PMButton(L("Back"), systemImage = "arrow.left", variant = ButtonVariant.outline, size = ButtonSize.compact, enabled = !busy, action = onBack)
            }
            Spacer(Modifier.weight(1f))
            if (onSkip != null) {
                PMButton(L("For later"), variant = ButtonVariant.link, size = ButtonSize.slim, tint = Theme.inkStrong, enabled = !busy, action = onSkip)
            }
            PMButton(
                if (busy) L("Saving…") else nextLabel, systemImage = if (busy) null else "arrow.right", size = ButtonSize.compact,
                isWorking = busy, enabled = !nextDisabled, action = onNext,
            )
        }
        if (note != null) Text(note, style = Theme.smallFont, color = Theme.inkSoft)
    }
}

/** The frame with only the questions (the Swift's `where Aside == EmptyView` init). */
@Composable
fun HomeWelcomeStepFrame(
    state: HomeWelcomeState,
    step: String,
    title: String,
    description: String,
    nextLabel: String,
    busy: Boolean = false,
    nextDisabled: Boolean = false,
    note: String? = null,
    onNext: () -> Unit,
    onBack: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    HomeWelcomeStepFrame(
        state = state, step = step, title = title, description = description, nextLabel = nextLabel, busy = busy,
        nextDisabled = nextDisabled, note = note, onNext = onNext, onBack = onBack, onSkip = onSkip, modifier = modifier,
        content = content, aside = {},
    )
}

/** A titled block inside a step (the web's SetupBlock). */
@Composable
fun HomeWelcomeBlock(
    systemImage: String,
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = systemImage, accent = Theme.blue, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.accent, washPercent = 10.0)
            Text(title, style = Theme.sectionFont, color = Theme.inkStrong)
        }
        if (hint != null) Text(hint, style = Theme.smallFont, color = Theme.inkSoft)
        content()
    }
}

// MARK: - 1. You

@Serializable
private data class HomeWelcomeUploaded(val photoUrl: String? = null)

@Composable
private fun HomeWelcomeProfileStep(
    state: HomeWelcomeState,
    onSaved: (HomeWelcomeState) -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val form = remember { FormState() }
    // The Swift fills these once on appear (`started`); here they start with it.
    val name = remember { mutableStateOf(state.profile?.displayName ?: state.profile?.accountName ?: "") }
    val title = remember { mutableStateOf(state.profile?.professionalTitle ?: "") }
    val phone = remember { mutableStateOf(state.profile?.phone ?: "") }
    val photo = remember { mutableStateOf(PhotoDraft()) }
    var uploading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var photoURL by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val categories = state.welcome?.categories ?: emptyList()
    val role = HomeWelcomeText.roleLabel(state.welcome?.role, categories)
    val shown = HomeWelcomeState.printedName(name.value, state.profile?.accountName)

    suspend fun upload(data: ByteArray) {
        uploading = true
        try {
            val result: HomeWelcomeUploaded = ImageUpload.upload(data, to = "/provider-member-onboarding/photo")
            photoURL = result.photoUrl
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(errorText(e, L("Could not upload the photo.")))
        } finally {
            uploading = false
            photo.value = PhotoDraft()
        }
    }

    fun save() {
        val clean = name.value.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val digits = phone.value.filter { it.isDigit() }
        if (!form.check(listOf(
                "name" to (if (clean.length < 2) L("Type your name as it should print.") else null),
                "phone" to (if (phone.value.trim().isNotEmpty() && digits.length < 10) L("Finish the phone number, or leave it empty.") else null),
            ))) return
        saving = true
        scope.launch {
            try {
                val next: HomeWelcomeState = API.put(
                    "/provider-member-onboarding/profile",
                    mapOf("displayName" to clean, "professionalTitle" to title.value.nonEmpty, "phone" to phone.value.nonEmpty),
                )
                onSaved(next)
                announceDataChanged()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                form.apply(e, known = mapOf("displayName" to "name", "phone" to "phone"))
            } finally {
                saving = false
            }
        }
    }

    // The photo goes up as soon as it is chosen (iOS `.onChange(of: photo.data)`).
    val picked = photo.value.data
    LaunchedEffect(picked) {
        val data = picked ?: return@LaunchedEffect
        scope.launch { upload(data) }
    }

    HomeWelcomeStepFrame(
        state = state, step = "profile", title = L("You, on the team"),
        description = L("How your name prints on prescriptions, receipts and reports, and a photo so the team and families recognise you."),
        nextLabel = L("Save and continue"), busy = saving, onNext = { save() }, onSkip = onSkip,
    ) {
        FormScroll(form = form) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeWelcomeBlock(systemImage = "person.fill", title = L("Photo and name")) {
                    // The photo goes up as soon as it is chosen (the welcome saves it on its own).
                    Disabled(uploading) {
                        PhotoField(
                            label = L("Photo"), hint = L("Optional. JPG, PNG or WebP up to 3 MB."), currentURL = photoURL ?: state.profile?.photoUrl,
                            draft = photo, shape = PhotoField.Shape.circle, allowsRemove = false,
                        )
                    }
                    PMTextField(
                        L("Name as it prints on documents"), text = name, key = "name", form = form,
                        hint = L("With your title if you use one, the way you sign."), capitalization = KeyboardCapitalization.Words, required = true,
                    )
                    PMTextField(
                        L("Title"), text = title, prompt = HomeWelcomeText.titlePrompt(state.welcome?.role, categories, state.welcome?.country),
                        hint = L("Optional. Printed under your name."), optional = true,
                    )
                }
                HomeWelcomeBlock(systemImage = "phone.fill", title = L("Phone"), hint = L("So the team can reach you. It is your account’s number.")) {
                    PMTextField(L("Mobile phone"), text = phone, key = "phone", form = form, keyboard = KeyboardType.Phone, optional = true)
                }
                // How it prints: the line under a signature.
                val card = RoundedCornerShape(Theme.cardRadius)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { contentDescription = L("How your name prints") }
                        .background(Theme.surface, card)
                        .border(1.dp, Theme.borderSoft, card)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(L("How it prints").uppercase(), style = brandText(10, FontWeight.ExtraBold).tracking(1.4), color = Theme.inkSoft)
                    Box(Modifier.padding(top = 22.dp).fillMaxWidth().height(1.dp).background(Theme.borderSoft))
                    Text(shown.ifEmpty { "—" }, style = brandHeading(18), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title.value.nonEmpty ?: role, style = Theme.smallFont, color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.businessName, style = Theme.captionFont, color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// MARK: - 2. Language and alerts

/** One category's channels. */
private data class HomeWelcomeChannels(val inApp: Boolean, val email: Boolean)

@Composable
private fun HomeWelcomePreferencesStep(
    state: HomeWelcomeState,
    onDone: suspend () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val workspace = Workspace.shared
    // The Swift sets it once in `.task` (`started`); here it starts with it.
    val language = remember { mutableStateOf(state.preferences?.language ?: LocalizationManager.currentLanguage.rawValue) }
    val channels = remember { mutableStateMapOf<String, HomeWelcomeChannels>() }
    var saved by remember { mutableStateOf<Map<String, TeamNotificationPreference>>(emptyMap()) }
    var timezone by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val categories = HomeWelcomeText.alertCategories(state.work?.permissions ?: emptyList())

    fun binding(category: String, email: Boolean) = stateOf(
        get = { if (email) (channels[category]?.email ?: false) else (channels[category]?.inApp ?: true) },
        set = { value: Boolean ->
            val current = channels[category] ?: HomeWelcomeChannels(inApp = true, email = false)
            channels[category] = if (email) current.copy(email = value) else current.copy(inApp = value)
        },
    )

    suspend fun load() {
        try {
            val clinicId = workspace.current?.clinicId ?: return
            val answer = runCatching {
                API.get<TeamNotificationPreferences>("/notification-preferences", mapOf("contextType" to "provider_workspace", "contextId" to clinicId))
            }.getOrNull()
            val items = answer?.items ?: emptyList()
            val byCategory = LinkedHashMap<String, TeamNotificationPreference>()
            for (item in items) if (!byCategory.containsKey(item.category)) byCategory[item.category] = item
            saved = byCategory
            timezone = items.firstOrNull()?.timezone
            for (category in categories) {
                val item = byCategory[category]
                channels[category] = HomeWelcomeChannels(
                    inApp = item?.inAppEnabled ?: true,
                    email = item?.emailEnabled ?: (category == "appointments" || category == "provider_follow_ups"),
                )
            }
        } finally {
            loaded = true
        }
    }

    fun save() {
        val clinicId = workspace.current?.clinicId ?: return
        busy = true
        scope.launch {
            try {
                if (categories.isNotEmpty()) {
                    val rows: List<Map<String, Any?>> = categories.map { category ->
                        val item = saved[category]
                        val chosen = channels[category] ?: HomeWelcomeChannels(inApp = true, email = false)
                        val leads = item?.reminderLeadTimes ?: emptyList()
                        mapOf(
                            "category" to category, "inAppEnabled" to chosen.inApp, "emailEnabled" to chosen.email,
                            "pushEnabled" to (item?.pushEnabled ?: true), "smsEnabled" to false,
                            "reminderLeadTimes" to (if (leads.isEmpty()) listOf(24 * 60, 0) else leads),
                        )
                    }
                    API.put<EmptyResponse>(
                        "/notification-preferences",
                        mapOf(
                            "contextType" to "provider_workspace", "contextId" to clinicId,
                            "timezone" to (timezone ?: ZoneId.systemDefault().id), "categories" to rows,
                        ),
                    )
                }
                if (state.preferences?.languageChosen != true || state.preferences?.language != language.value) {
                    API.patch<EmptyResponse>("/users/me/preferences", mapOf("preferredLanguage" to language.value))
                }
                // Saved before the language changes: the welcome picks up at the next step.
                onDone()
                val chosen = AppLanguage.from(language.value)
                if (chosen != null && chosen != LocalizationManager.currentLanguage) {
                    LocalizationManager.setLanguage(chosen)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(errorText(e, L("Could not save this step.")))
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    HomeWelcomeStepFrame(
        state = state, step = "preferences", title = L("Language and alerts"),
        description = L("Choose the language you work in and which alerts from this workspace reach you. You can change both any time in Settings."),
        nextLabel = L("Save and continue"), busy = busy, onNext = { save() }, onBack = onBack, onSkip = onSkip,
    ) {
        HomeWelcomeBlock(systemImage = "character.bubble.fill", title = L("Your language"), hint = L("For the app and the emails PawMiaw sends you. Families keep their own.")) {
            ChoiceCards(
                options = listOf(Triple("es", "Español", L("México y Latinoamérica")), Triple("en", "English", L("United States"))),
                selection = language,
            )
        }
        HomeWelcomeBlock(systemImage = "bell.fill", title = L("Alerts from this workspace"), hint = L("Only what your role handles. Security alerts always reach you.")) {
            if (!loaded) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Theme.inkSoft)
                }
            } else if (categories.isEmpty()) {
                Text(L("Your role gets no workspace alerts yet. Security alerts always reach you."), style = Theme.smallFont, color = Theme.inkSoft)
            } else {
                for (category in categories) {
                    key(category) {
                        val copy = HomeWelcomeText.alertCopy(category)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(copy.first, style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                            Text(copy.second, style = Theme.text(13), color = Theme.inkSoft)
                            HomeWelcomeGrid(items = listOf(false, true), minimum = 150.dp, spacing = 8.dp) { email ->
                                ToggleRow(title = if (email) L("Email") else L("In app"), isOn = binding(category, email), modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (category != categories.last()) StitchedDivider()
                    }
                }
            }
        }
        // This device's push: shown, marked, not built yet (as on the web).
        Row(
            Modifier
                .fillMaxWidth()
                .dashedBorder(1.5.dp, Theme.borderSoft, Theme.cardRadius, on = 5.dp, off = 5.dp)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            HomeDisc(icon = "smartphone", size = 40.dp, iconSize = 20.dp, fill = Theme.surface, ink = Theme.inkSoft)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L("This device"), style = Theme.sectionFont, color = Theme.inkStrong)
                    HueChip(text = L("Coming soon"), hue = Hue.grey, systemImage = "bell")
                }
                Text(L("Push notifications on this computer or phone. For now alerts arrive in the app and by email."), style = Theme.smallFont, color = Theme.inkSoft)
            }
        }
    }
}

// MARK: - 3. Where and how you work

@Composable
private fun HomeWelcomeWorkStep(
    state: HomeWelcomeState,
    onDone: suspend () -> Unit,
    onBack: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permissions = state.work?.permissions ?: emptyList()
    val can = HomeWelcomeText.abilities(permissions)
    val others = HomeWelcomeText.handledByOthers(permissions)
    val role = HomeWelcomeText.roleLabel(state.work?.role ?: state.welcome?.role, state.welcome?.categories ?: emptyList())
    val places = state.work?.locations ?: emptyList()
    val count = state.work?.locationCount ?: places.size
    val invitedBy = state.welcome?.invitedByName
    HomeWelcomeStepFrame(
        state = state, step = "work", title = L("Where and how you work"),
        description = LF("What your role at %@ lets you do. Nothing to fill in: just check it looks right.", state.businessName),
        nextLabel = L("Looks right"), busy = busy,
        note = if (invitedBy != null) LF("Something off? Ask %@ to change it on the Staff page.", invitedBy) else L("Something off? Ask the owner to change it on the Staff page."),
        onNext = {
            busy = true
            scope.launch {
                onDone()
                busy = false
            }
        },
        onBack = onBack,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(ProviderState.done.wash, RoundedCornerShape(Theme.cardRadius))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).background(Theme.blueFixed, RoundedCornerShape(Theme.boxRadius)), contentAlignment = Alignment.Center) {
                Glyph("user-cog", 24.dp, tint = Color.White)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L("Your role").uppercase(), style = brandText(11, FontWeight.ExtraBold).tracking(1.2), color = Theme.inkSoft)
                Text(role, style = brandHeading(24), color = Theme.inkStrong)
            }
        }
        HomeWelcomeBlock(systemImage = "checkmark.seal.fill", title = L("What you can do")) {
            if (can.isEmpty()) {
                Text(L("Your role can’t do anything yet. Ask the owner to give it permissions."), style = Theme.smallFont, color = Theme.inkSoft)
            } else {
                HomeWelcomeGrid(items = can, minimum = 240.dp, spacing = 8.dp) { line ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Theme.surfaceMuted, RoundedCornerShape(Theme.chipRadius))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        HomeDisc(icon = line.first, size = 28.dp, iconSize = 14.dp, fill = Theme.surface, ink = Theme.blue)
                        Text(line.second, Modifier.weight(1f), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                    }
                }
            }
            if (others.isNotEmpty()) {
                Text(
                    L("Someone else handles").uppercase(), Modifier.padding(top = 6.dp),
                    style = brandText(11, FontWeight.ExtraBold).tracking(1.2), color = Theme.inkSoft,
                )
                Text(others.joinToString(" · "), style = Theme.smallFont, color = Theme.inkSoft)
            }
        }
        HomeWelcomeBlock(systemImage = "mappin.and.ellipse", title = L("Where you work")) {
            if (state.work?.everyLocation == true) {
                HomeWelcomeGlyphLabel(
                    if (count > 1) LF("Every location (%d)", count) else LF("At %@", places.firstOrNull()?.name ?: state.businessName), icon = "check",
                    style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong,
                )
            }
            if (state.work?.everyLocation != true || count > 1) {
                FlowLayout(spacing = 8.dp) {
                    for (place in places) {
                        key(place.id) {
                            HueChip(text = (place.name ?: "—") + (if (place.isPrimary == true) " · " + L("main") else ""), hue = Hue.grey, systemImage = "mappin")
                        }
                    }
                }
            }
            if (state.work?.everyLocation != true) {
                Text(L("You see the visits, bills and stays of these locations."), style = Theme.smallFont, color = Theme.inkSoft)
            }
        }
    }
}

// MARK: - Pieces of this file

/** `GlyphLabel(…).font(f).foregroundStyle(c)`: the icon and the words in that font and ink. */
@Composable
private fun HomeWelcomeGlyphLabel(title: String, icon: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides color, LocalTextStyle provides style) {
        GlyphLabel(title, icon, modifier = modifier)
    }
}

/**
 * `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:)], spacing:)` inside a scrolling
 * page: as many equal columns as fit at `minimum` wide, rows `spacing` apart.
 */
@Composable
private fun <T> HomeWelcomeGrid(items: List<T>, minimum: Dp, spacing: Dp, cell: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
                    chunk.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
