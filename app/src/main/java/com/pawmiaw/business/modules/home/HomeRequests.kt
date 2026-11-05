package com.pawmiaw.business.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import com.pawmiaw.business.stays.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

//
//  HomeRequests.kt (iOS HomeRequests.swift)
//
//  Families' requests answered from Home (web requests/provider-requests.tsx,
//  backend docs/family-requests.md). Hotel stays, daycare and home stays are
//  the Stays module's own list and sheets (`StaysRequestsView`), and the
//  online bookings beside them answer on their visit. A program (training
//  sessions) is answered here: confirm by booking its weekly sessions,
//  propose another start or decline with a reason; the family is told.
//

/** The programs families asked for, as cards that open their answer. */
@Composable
fun HomeProgramRequests(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    var open by remember { mutableStateOf<List<StaysRequest>>(emptyList()) }
    var answered by remember { mutableStateOf<List<StaysRequest>>(emptyList()) }
    var showAnswered by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<StaysRequest?>(null) }
    var seenShowAnswered by remember { mutableStateOf(showAnswered) }

    suspend fun load() {
        val location = workspace.locationId
        val list = runCatching { API.get<StaysRequestList>("/provider-requests", mapOf("scope" to "open", "locationId" to location)) }.getOrNull()
        open = (list?.items ?: emptyList()).filter { it.kind == "program" }
        if (showAnswered) {
            val all = runCatching { API.get<StaysRequestList>("/provider-requests", mapOf("scope" to "all", "locationId" to location)) }.getOrNull()
            answered = (all?.items ?: emptyList()).filter { it.kind == "program" && it.status !in listOf("pending", "proposed") }
        }
        val current = focused
        if (current != null) focused = (open + answered).firstOrNull { it.id == current.id } ?: current
    }

    ReloadsOnScope { load() }
    // `.onChange(of: showAnswered)`: not on the first appearance.
    LaunchedEffect(showAnswered) {
        if (seenShowAnswered != showAnswered) {
            seenShowAnswered = showAnswered
            load()
        }
    }

    @Composable
    fun group(title: String, items: List<StaysRequest>) {
        if (items.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StaysEyebrow(text = title)
                HomeRequestsGrid(items = items, minimum = 300.dp, spacing = 10.dp) { request ->
                    HomeProgramCard(request = request, modifier = Modifier.plainButton { focused = request })
                }
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        group(L("Programs waiting for you"), open.filter { it.shownStatus == "pending" })
        group(L("Programs waiting for the family"), open.filter { it.shownStatus == "proposed" })
        group(L("Programs with dates passed"), open.filter { it.shownStatus == "expired" })
        if (open.isNotEmpty() || answered.isNotEmpty()) {
            PMButton(
                if (showAnswered) L("Hide answered programs") else L("Show answered programs"),
                variant = ButtonVariant.outline, size = ButtonSize.compact,
            ) { showAnswered = !showAnswered }
        }
        if (showAnswered) group(L("Answered programs"), answered)
    }
    focused?.let { request ->
        AppSheet(onDismissRequest = { focused = null }) { HomeProgramRequestSheet(request = request) }
    }
}

/** SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:, alignment: .top)], spacing:)`. */
@Composable
private fun <T> HomeRequestsGrid(items: List<T>, minimum: Dp, spacing: Dp, cell: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (row in items.chunked(columns)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    for (item in row) {
                        Box(Modifier.weight(1f)) { cell(item) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HomeProgramCard(request: StaysRequest, modifier: Modifier = Modifier) {
    val status = StaysWords.requestStatus(request)
    Card(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).background(Theme.wash(Theme.blue, 14.0), RoundedCornerShape(Theme.chipRadius)),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(StaysWords.requestKindIcon(request.kind), 20.dp, tint = Theme.blue)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(request.petNames, style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${request.clientName ?: L("A family")} · ${StaysWords.requestKind(request.kind)}",
                    style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            StatusChip(text = status.first, state = status.second)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("calendar-range", 16.dp, tint = Theme.inkSoft)
            Text(StaysWords.requestRange(request.kind, request.startDay, request.endDay), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
        }
        val start = request.proposedStartDay
        if (request.status == "proposed" && start != null) {
            Text(
                LF("You proposed %@", StaysWords.requestRange(request.kind, start, request.proposedEndDay ?: start)),
                style = Theme.text(12), color = Theme.inkSoft,
            )
        }
        request.program.nonEmpty?.let { Text(it, style = Theme.smallFont, color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        request.notes.nonEmpty?.let { Text("“$it”", style = Theme.text(14), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

private enum class HomeAnswerMode { confirm, propose, decline }

/** One program request: what was asked, and the three answers. */
@Composable
fun HomeProgramRequestSheet(request: StaysRequest, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val mode = remember { mutableStateOf(HomeAnswerMode.confirm) }
    var services by remember { mutableStateOf<List<SchedCatalogService>>(emptyList()) }
    var members by remember { mutableStateOf<List<SchedMember>>(emptyList()) }
    var serviceId by remember { mutableStateOf("") }
    val memberId = remember { mutableStateOf("") }
    val firstDay = remember { mutableStateOf("") }
    val time = remember { mutableStateOf("09:00") }
    val sessions = remember { mutableStateOf(1) }
    val duration = remember { mutableStateOf(60) }
    val message = remember { mutableStateOf("") }
    val startDay = remember { mutableStateOf("") }
    val reason = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    val expired = request.shownStatus == "expired"
    val canAnswer = request.status == "pending" && workspace.can(Permission.appointmentsCreate)

    val modalTitle = "${StaysWords.requestKind(request.kind)} · ${request.petNames}"
    val modalSubtitle = "${request.clientName ?: L("A family")} · ${StaysWords.requestRange(request.kind, request.startDay, request.endDay)}"
    val answerOptions = buildList {
        if (!expired) add(Segmented.Option(HomeAnswerMode.confirm, L("Confirm"), icon = "check"))
        add(Segmented.Option(HomeAnswerMode.propose, L("Other dates"), icon = "calendar-clock"))
        add(Segmented.Option(HomeAnswerMode.decline, L("Decline"), icon = "circle-x"))
    }

    val visits: List<Map<String, Any?>> = (0 until maxOf(1, minOf(12, sessions.value))).map { index ->
        mapOf("date" to StaysDay.add(firstDay.value, index * 7), "startTime" to time.value, "durationMinutes" to duration.value)
    }

    val serviceOptions: List<Pair<String, String>> =
        if (services.isEmpty()) listOf("" to StaysWords.requestKind(request.kind)) else services.map { it.id to it.label }
    val memberOptions: List<Pair<String, String>> = listOf("" to L("Decide later")) + members.map { it.id to it.label }
    val sessionOptions: List<Pair<Int, String>> = (1..12).map { it to it.toString() }
    val lengthOptions: List<Pair<Int, String>> = (HomeProgramRequestSheetRules.lengths + duration.value).toSet().sorted().map { it to HomeProgramRequestSheetRules.minutesLabel(it) }
    val serviceBinding = stateOf({ serviceId }, { id ->
        serviceId = id
        services.firstOrNull { it.id == id }?.minutes?.let { duration.value = it }
    })

    val totalLine = run {
        val pets = (request.pets ?: emptyList()).size
        val total = visits.size * maxOf(1, pets)
        val first = if (total == 1) L("1 visit will be booked.") else LF("%d visits will be booked.", total)
        if (pets > 1) first + " " + L("One per pet, at the same time.") else first
    }

    val primaryTitle = when (mode.value) {
        HomeAnswerMode.confirm -> L("Confirm and book")
        HomeAnswerMode.propose -> L("Send these dates")
        HomeAnswerMode.decline -> L("Decline request")
    }

    fun run(success: String, work: suspend () -> Unit) {
        working = true
        scope.launch {
            try {
                work()
                Toast.show(success)
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(StaysWords.requestError(e))
            } finally {
                working = false
            }
        }
    }

    fun submit() {
        when (mode.value) {
            HomeAnswerMode.confirm -> {
                if (!form.check(listOf("firstDay" to (if (firstDay.value < StaysDay.today()) L("Choose a date from today on.") else null)))) return
                run(L("Confirmed. The visits are in the schedule and the family has been told.")) {
                    API.send(
                        "POST", "/provider-requests/${request.id}/confirm",
                        mapOf("serviceId" to serviceId.nonEmpty, "memberId" to memberId.value.nonEmpty, "visits" to visits, "message" to message.value.nonEmpty),
                    )
                }
            }
            HomeAnswerMode.propose -> {
                if (!form.check(listOf("dates" to (if (startDay.value < StaysDay.today()) L("Choose a date from today on.") else null)))) return
                run(L("Dates sent to the family.")) {
                    API.send(
                        "POST", "/provider-requests/${request.id}/propose",
                        mapOf("startDay" to startDay.value, "endDay" to startDay.value, "message" to message.value.nonEmpty),
                    )
                }
            }
            HomeAnswerMode.decline -> {
                val why = reason.value.trim()
                if (!form.check(listOf("reason" to (if (why.length < 2) L("Tell the family why.") else null)))) return
                run(L("Declined. The family has been told why.")) {
                    API.send("POST", "/provider-requests/${request.id}/decline", mapOf("reason" to why))
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        mode.value = if (expired) HomeAnswerMode.propose else HomeAnswerMode.confirm
        val today = StaysDay.today()
        firstDay.value = if (request.startDay < today) today else request.startDay
        startDay.value = firstDay.value
        if (!canAnswer) return@LaunchedEffect
        coroutineScope {
            val catalogCall = async { SchedData.catalog() }
            val teamCall = async { SchedData.members() }
            val catalog = catalogCall.await()
            services = (catalog?.services ?: emptyList()).filter { it.category == "trainer" }
            members = teamCall.await().filter { it.status != "suspended" }
        }
        val first = services.firstOrNull()
        if (serviceId.isEmpty() && first != null) {
            serviceId = first.id
            first.minutes?.let { duration.value = it }
        }
    }

    val status = StaysWords.requestStatus(request)
    val variant = if (mode.value == HomeAnswerMode.decline) ButtonVariant.destructive else ButtonVariant.primary
    ProviderModal(
        title = modalTitle, subtitle = modalSubtitle, systemImage = StaysWords.requestKindIcon(request.kind), accent = ModalAccent.aqua,
        isDirty = message.value.isNotEmpty() || reason.value.isNotEmpty(), form = form,
        primaryTitle = if (canAnswer) primaryTitle else null, primaryVariant = variant,
        isWorking = working, cancelTitle = if (canAnswer) L("Cancel") else L("Close"),
        onPrimary = if (canAnswer) ({ submit() }) else null,
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        StatusChip(text = status.first, state = status.second)
        ModalSection(title = L("What they asked for")) {
            FactRow(label = L("Start"), value = StaysWords.requestRange(request.kind, request.startDay, request.endDay))
            if (request.familyAcceptedAt != null) {
                Text(L("The family accepted these dates you proposed."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.blueText)
            }
            FactRow(label = L("Family"), value = request.clientName ?: L("A family"))
            request.clientPhone.nonEmpty?.let { PhoneLink(phone = it) }
            request.program.nonEmpty?.let { FactRow(label = L("What they are looking for"), value = it) }
            request.location?.name?.let { FactRow(label = L("Location"), value = it) }
            request.notes.nonEmpty?.let { StaysNoteBlock(label = L("Notes"), text = "“$it”") }
        }
        // The outcome so far.
        when (request.status) {
            "proposed" -> {
                val start = request.proposedStartDay ?: request.startDay
                NoticeCard(
                    tone = NoticeCard.Tone.info,
                    title = LF("You proposed %@", StaysWords.requestRange(request.kind, start, request.proposedEndDay ?: start)),
                    message = (request.providerMessage.nonEmpty?.let { "“$it” " } ?: "") + L("Waiting for the family to accept or cancel."),
                )
            }
            "declined" -> NoticeCard(tone = NoticeCard.Tone.problem, title = L("Declined"), message = request.providerMessage?.let { "“$it”" })
            "cancelled" -> NoticeCard(tone = NoticeCard.Tone.info, title = L("The family cancelled this request"))
            "confirmed" -> {
                val ids = request.appointmentIds ?: emptyList()
                NoticeCard(
                    tone = NoticeCard.Tone.success, title = L("Confirmed"),
                    message = if (ids.isEmpty()) null else LP(ids.size, "%d visit booked.", "%d visits booked."),
                )
            }
            else -> {}
        }
        if (canAnswer) {
            // The web's answer panel: the three answers as one switch (the online bookings' too), on a pale blue card.
            val shape = RoundedCornerShape(Theme.cardRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.blue, 5.0), shape)
                    .border(1.dp, Theme.borderSoft, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Segmented(options = answerOptions, selection = mode, label = L("Answer"))
                when (mode.value) {
                    HomeAnswerMode.confirm -> {
                        MenuField(label = L("Service"), options = serviceOptions, selection = serviceBinding)
                        MenuField(label = L("Who goes"), options = memberOptions, selection = memberId)
                        DayField(label = L("First session"), day = firstDay, min = StaysDay.today(), key = "firstDay", form = form)
                        TimeField(label = L("Time"), value = time)
                        MenuField(label = L("Weekly sessions"), options = sessionOptions, selection = sessions)
                        MenuField(label = L("Length of each visit"), options = lengthOptions, selection = duration)
                        PMTextField(L("A line for the family (optional)"), text = message)
                        Text(totalLine, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                    }
                    HomeAnswerMode.propose -> {
                        DayField(label = L("Start"), day = startDay, min = StaysDay.today(), key = "dates", form = form)
                        PMTextField(
                            L("A line for the family (optional)"), text = message,
                            prompt = L("e.g. We are full that weekend, the next one is free."), multiline = true,
                        )
                    }
                    HomeAnswerMode.decline -> {
                        FlowLayout(spacing = 6.dp) {
                            for (quick in listOf("We are full on those dates.", "We can’t take this pet.", "We don’t offer that at the moment.")) {
                                key(quick) {
                                    FilterChip(title = L(quick), isSelected = reason.value == L(quick)) {
                                        reason.value = L(quick)
                                        form.clear("reason")
                                    }
                                }
                            }
                        }
                        PMTextField(L("Why (the family reads this)"), text = reason, key = "reason", form = form, multiline = true)
                    }
                }
            }
        } else if (request.status == "pending") {
            Text(L("Someone with access to book this can answer it."), style = Theme.smallFont, color = Theme.inkSoft)
        }
    }
}

/** `HomeProgramRequestSheet`'s static rules (iOS `lengths`, `minutesLabel`). */
object HomeProgramRequestSheetRules {
    val lengths = listOf(30, 45, 60, 90, 120, 180, 240, 480, 720)

    fun minutesLabel(minutes: Int): String {
        if (minutes < 60) return LF("%d min", minutes)
        if (minutes % 60 == 0) return if (minutes == 60) L("1 hour") else LF("%d hours", minutes / 60)
        return LF("%@ h", String.format(Locale.US, "%.1f", minutes / 60.0))
    }
}
