package com.pawmiaw.business.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

//
//  DueScreen.kt (iOS DueScreen.swift)
//
//  Due (web: routes/provider.due.tsx and due/due-list.tsx): who should come
//  back — vaccines and rechecks from follow-ups, grooming by each pet's
//  cadence — in three buckets, with a reminder (WhatsApp opens with the
//  message, or PawMiaw tells the family through the API) and a booking.
//  The app itself never sends a message: the reminder in PawMiaw is the
//  server's action, as on the web.
//
//  The page as the web draws it: the module header (its three numbers are
//  the bucket filter), the reason chips with "Próximos N días", then one
//  heading and one card per bucket, each row with the bucket's colour down
//  its left edge.
//

private val dueWindows = listOf(7, 30, 60, 90)
private val dueReasons = listOf("all", "vaccine", "recheck", "grooming", "follow_up")
private val dueBuckets = listOf("overdue", "this_week", "later")

@Composable
fun DueScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    var windowDays by remember { mutableStateOf(30) }
    var reason by remember { mutableStateOf("all") }
    var bucket by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<ClientsDueList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf<String?>(null) }

    val allowed = workspace.can(Permission.followupsView) || workspace.can(Permission.appointmentsCreate)
    val canRemind = workspace.can(Permission.followupsEdit) || workspace.can(Permission.appointmentsCreate)
    val canBook = workspace.can(Permission.appointmentsCreate) && workspace.can(Permission.clientsView)
    /** Closing a follow-up, or putting it off, is working on follow-ups. */
    val canWorkFollowUps = workspace.can(Permission.followupsEdit)

    /**
     * The visit to book for a row: its pet, the service the follow-up knows (a vaccine as a vaccination, a recheck as a
     * medical follow-up), the day it is due (today when that has passed) and the follow-up the visit answers.
     */
    fun booking(item: ClientsDueItem): BookingPrefill {
        val serviceId: String? = run {
            if (item.reason == "grooming") return@run null
            item.service?.id.nonEmpty?.let { return@run it }
            if (!workspace.offers(BusinessCategory.vet, BusinessCategory.emergency)) return@run null
            if (item.reason == "vaccine") "vaccination" else "medical_follow_up"
        }
        val now = Instant.now()
        val due = DateText.parse(item.dueAt)
        val day = DateText.dayKey(due?.let { if (it < now) now else it } ?: now, zone = workspace.timeZone)
        return BookingPrefill(clientId = item.client.id, petId = item.pet.id, serviceId = serviceId, day = day, followUpId = item.followUpId)
    }

    suspend fun load() {
        if (!allowed) return
        try {
            list = API.get("/provider-due", mapOf("window" to "$windowDays"))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
    }

    // MARK: Actions

    /** A reminder in PawMiaw (the server writes to the family), or a WhatsApp chat opened with the message. */
    fun remind(item: ClientsDueItem, channel: String) {
        working = item.key
        val locale = LocalizationManager.currentLanguage.rawValue
        scope.launch {
            try {
                val followUpId = item.followUpId
                val result: ClientsRemindResult = if (followUpId != null) {
                    API.post("/provider-follow-ups/$followUpId/remind", body = mapOf("channel" to channel, "locale" to locale))
                } else {
                    API.post("/provider-due/remind", body = mapOf("providerClientPetId" to item.pet.id, "reason" to "grooming", "channel" to channel, "locale" to locale))
                }
                val link = result.link.nonEmpty
                if (channel == "whatsapp" && link != null) {
                    openURL(link)
                    Toast.show(L("WhatsApp is open with the message."))
                } else {
                    Toast.show(L("Reminder sent in PawMiaw."))
                }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                working = null
            }
        }
    }

    /** Closing a follow-up from the list: it was done somewhere else, or no longer applies. */
    fun done(item: ClientsDueItem) {
        val followUpId = item.followUpId ?: return
        working = item.key
        scope.launch {
            try {
                API.patch<EmptyResponse>("/provider-follow-ups/$followUpId", body = mapOf("status" to "done"))
                Toast.show(L("Marked as done."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                working = null
            }
        }
    }

    /** Putting a follow-up off: N days after the later of today and the day it is due (the business's calendar). */
    fun snooze(item: ClientsDueItem, days: Int) {
        val followUpId = item.followUpId ?: return
        val zone = workspace.timeZone
        val now = Instant.now()
        val due = DateText.parse(item.dueAt) ?: now
        val from = maxOf(due, now).atZone(zone).toLocalDate()
        val until = from.plusDays(days.toLong()).toString()
        working = item.key
        scope.launch {
            try {
                API.patch<EmptyResponse>("/provider-follow-ups/$followUpId", body = mapOf("status" to "snoozed", "snoozedUntil" to until))
                Toast.show(LF("Postponed until %@.", ClientsText.shortDay(until)))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                working = null
            }
        }
    }

    fun toggleBucket(key: String) {
        bucket = if (bucket == key) null else key
    }

    // MARK: Header

    /** The header's numbers are filters too: one bucket at a time. */
    @Composable
    fun headerStats(): List<ModuleStat> {
        val counts = list?.counts ?: return emptyList()
        val overdue = counts.overdue ?: 0
        return listOf(
            ModuleStat(
                "overdue", label = dueBucketTitle("overdue"), value = "$overdue", icon = "bell-ring", accent = Theme.pink,
                attention = overdue > 0, active = bucket == "overdue", action = { toggleBucket("overdue") },
            ),
            ModuleStat(
                "week", label = dueBucketTitle("this_week"), value = "${counts.thisWeek ?: 0}", icon = "calendar-plus", accent = Theme.mango,
                active = bucket == "this_week", action = { toggleBucket("this_week") },
            ),
            ModuleStat(
                "later", label = dueBucketTitle("later"), value = "${counts.later ?: 0}", icon = "calendar-clock", accent = Theme.aqua,
                active = bucket == "later", action = { toggleBucket("later") },
            ),
        )
    }

    // MARK: A row

    fun showsFamilyNotice(item: ClientsDueItem): Boolean =
        item.familyNotice?.asked == true && item.familyNotice?.sentAt == null && item.lastContact == null && item.nextVisit == null &&
            item.snoozedUntil == null && item.bucket != "overdue"

    /** "3 days overdue · 4 nov · Carmen Vázquez · last visit 2 oct". */
    @Composable
    fun Meta(item: ClientsDueItem, days: Int) {
        FlowLayout(spacing = 6.dp, lineSpacing = 2.dp) {
            Text(ClientsText.dueWords(days), style = Theme.text(12, FontWeight.Bold), color = if (item.bucket == "overdue") Theme.destructive else Theme.inkStrong)
            Text("·", style = Theme.text(12), color = Theme.inkSoft)
            Text(ClientsText.shortDay(item.dueAt), style = Theme.text(12), color = Theme.inkSoft)
            Text("·", style = Theme.text(12), color = Theme.inkSoft)
            Text(
                item.client.name, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.plainButton { nav.push(ProviderRoute.client(item.client.id)) },
            )
            item.lastVisitAt?.let { last ->
                Text("·", style = Theme.text(12), color = Theme.inkSoft)
                Text(LF("last visit %@", ClientsText.shortDay(last)), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }

    @Composable
    fun Chips(item: ClientsDueItem) {
        val hasAny = item.missedVisitAt != null || item.nextVisit != null || item.snoozedUntil != null || item.lastContact != null || showsFamilyNotice(item)
        if (!hasAny) return
        FlowLayout(spacing = 6.dp) {
            item.missedVisitAt?.let { missed ->
                // A visit cancelled before its day never "didn't happen": it was cancelled.
                val cancelled = DateText.parse(missed)?.let { it > Instant.now() } ?: false
                Chip(
                    text = if (cancelled) LF("The visit on %@ was cancelled", ClientsText.shortDay(missed)) else LF("The visit on %@ didn’t happen", ClientsText.shortDay(missed)),
                    tone = Chip.Tone.custom(Theme.mango), icon = "calendar-x-2", percent = 26.0, ink = Theme.inkStrong,
                )
            }
            item.nextVisit?.let { next ->
                // Already on the calendar: so nobody calls a family that has booked.
                val text = LF("Has a visit on %@: %@", ClientsText.shortDay(next.startsAt), next.serviceLabel ?: "")
                if (workspace.can(Permission.appointmentsView)) {
                    Box(Modifier.plainButton { nav.push(ProviderRoute.appointment(next.id)) }) {
                        Chip(text = text, tone = Chip.Tone.custom(Theme.blue), icon = "calendar-check", percent = 14.0, ink = Theme.inkStrong)
                    }
                } else {
                    Chip(text = text, tone = Chip.Tone.custom(Theme.blue), icon = "calendar-check", percent = 14.0, ink = Theme.inkStrong)
                }
            }
            item.snoozedUntil?.let { until ->
                Chip(text = LF("Postponed until %@", ClientsText.shortDay(until)), tone = Chip.Tone.muted, icon = "clock-3", ink = Theme.inkStrong)
            }
            val due = DateText.parse(item.dueAt)
            if (showsFamilyNotice(item) && due != null) {
                // PawMiaw tells the family a week before, once: so nobody sends it twice by hand.
                Chip(
                    text = LF("PawMiaw tells the family on %@", ClientsText.shortDay(DateText.iso(due.minus(Duration.ofDays(7))))),
                    tone = Chip.Tone.custom(Theme.blue), icon = "smartphone", percent = 14.0, ink = Theme.inkStrong,
                )
            }
            item.lastContact?.let { contact ->
                // Already told: so nobody messages the same family twice.
                Chip(
                    text = if (contact.channel == "whatsapp") LF("WhatsApp opened %@", ClientsText.shortDay(contact.at)) else LF("Reminded in PawMiaw %@", ClientsText.shortDay(contact.at)),
                    tone = Chip.Tone.custom(ProviderState.done.color), icon = "check-check", percent = 16.0, ink = Theme.inkStrong,
                )
            }
        }
    }

    @Composable
    fun Actions(item: ClientsDueItem, busy: Boolean, modifier: Modifier) {
        FlowLayout(spacing = 8.dp, modifier = modifier) {
            if (canRemind && item.client.phone.nonEmpty != null) {
                PMButton("WhatsApp", systemImage = "message-circle", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = busy, enabled = working == null) {
                    remind(item, channel = "whatsapp")
                }
            }
            if (canRemind && item.client.onPawMiaw == true) {
                PMButton(L("Remind in PawMiaw"), systemImage = "smartphone", variant = ButtonVariant.outline, size = ButtonSize.compact, enabled = working == null) {
                    remind(item, channel = "app")
                }
            }
            if (canBook) {
                // Once the pet has a visit, booking is no longer the row's main thing.
                PMButton(
                    L("Book"), systemImage = "calendar-plus",
                    variant = if (item.nextVisit == null) ButtonVariant.primary else ButtonVariant.outline, size = ButtonSize.compact,
                ) { nav.push(ProviderRoute.bookVisit(booking(item))) }
            }
            if (canWorkFollowUps && canRemind && item.followUpId != null) {
                AppMenu(
                    label = {
                        PMButtonStyle(variant = ButtonVariant.outline, size = ButtonSize.compact) {
                            PMButtonContent(L("Postpone"), icon = "clock-3", variant = ButtonVariant.outline, size = ButtonSize.compact)
                        }
                    },
                    enabled = working == null,
                ) {
                    item(L("1 week")) { snooze(item, days = 7) }
                    item(L("2 weeks")) { snooze(item, days = 14) }
                    item(L("1 month")) { snooze(item, days = 30) }
                }
                val hint = L("It was done, or it no longer applies: take it off the list.")
                PMButton(
                    L("Done"), systemImage = "circle-check", variant = ButtonVariant.outline, size = ButtonSize.compact, enabled = working == null,
                    modifier = Modifier.semantics { onClick(label = hint) { done(item); true } },
                ) { done(item) }
            }
        }
    }

    @Composable
    fun DueRow(item: ClientsDueItem, tone: Color) {
        val days = item.days ?: ClientsText.daysUntil(item.dueAt)
        val busy = working == item.key
        Column(
            Modifier
                .fillMaxWidth()
                // The bucket's colour down the left edge, as on Home: red is late, mango is this week.
                .clientsLeadingEdge(tone, 6.dp)
                .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                // The pet's photo and name open its chart.
                Box(Modifier.plainButton { nav.push(ProviderRoute.clientPet(clientId = item.client.id, petId = item.pet.id)) }) {
                    PetAvatar(name = item.pet.name, species = item.pet.species, photoURL = item.pet.photoUrl, size = 48.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowLayout(spacing = 8.dp, lineSpacing = 4.dp) {
                        Text(
                            item.pet.name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.plainButton { nav.push(ProviderRoute.clientPet(clientId = item.client.id, petId = item.pet.id)) },
                        )
                        Chip(
                            text = if (item.reason == "grooming" || item.title.nonEmpty == null) dueReasonLabel(item.reason) else ClientsText.followUpTitle(item.title),
                            tone = Chip.Tone.custom(dueReasonTone(item.reason)), icon = dueReasonIcon(item.reason), percent = 26.0, ink = Theme.inkStrong,
                        )
                    }
                    Meta(item, days)
                    Chips(item)
                }
                // Calling the family from the row (iOS keeps it; the web's row has none).
                ContactLinks.phone(item.client.phone)?.let { tel ->
                    IconCircleButton(systemImage = "phone", label = L("Call")) { openURL(tel) }
                }
            }
            Actions(item, busy, Modifier.padding(start = 60.dp))
        }
    }

    /** A bucket: its dot, name and count over one card of rows. */
    @Composable
    fun BucketSection(key: String, items: List<ClientsDueItem>) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(dueBucketTone(key), CircleShape))
                Text(dueBucketTitle(key), style = Theme.heading(18), color = Theme.inkStrong)
                Text(
                    "${items.size}", style = Theme.text(12, FontWeight.ExtraBold).monospacedDigit(), color = Theme.inkStrong,
                    modifier = Modifier.background(Theme.surfaceMuted, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Card(padding = 0.dp, spacing = 0.dp) {
                Column(Modifier.clip(RoundedCornerShape(Theme.cardRadius))) {
                    items.forEachIndexed { index, item ->
                        androidx.compose.runtime.key(item.id) {
                            if (index > 0) RowDivider()
                            DueRow(item, tone = dueBucketTone(key))
                        }
                    }
                }
            }
        }
    }

    providerPage(L("Due"), root = true, modifier = modifier) {
        if (!allowed) {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see follow-ups.")) }
        } else {
            PageScroll(refresh = { load() }) {
                ModuleHeader(
                    icon = "bell-ring",
                    title = L("Due"),
                    subtitle = L("Who should come back — vaccines, rechecks and grooming — with a reminder and a booking in one tap."),
                    stats = headerStats(),
                    storageKey = "due",
                )
                // Each reason in its own colour (a dot), with how many; then how far ahead to look.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowLayout(spacing = 6.dp) {
                        dueReasons.forEach { key ->
                            val count = list?.let { l -> if (key == "all") l.items.size else l.items.count { it.reason == key } }
                            FilterChip(
                                title = dueReasonFilterLabel(key), count = count, isSelected = reason == key,
                                tone = if (key == "all") Theme.blue else dueReasonTone(key), dot = if (key == "all") null else dueReasonTone(key),
                            ) { reason = key }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(L("Next@window"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
                        AppMenu(label = { FilterMenuLabel(title = LF("%d days", windowDays)) }) {
                            dueWindows.forEach { days ->
                                item(LF("%d days", days), checked = days == windowDays) {
                                    windowDays = days
                                    scope.launch { load() }
                                }
                            }
                        }
                    }
                }
                // MARK: The buckets
                val current = list
                val failure = error
                if (current != null) {
                    val items = current.items.filter { (reason == "all" || it.reason == reason) && (bucket == null || it.bucket == bucket) }
                    if (items.isEmpty()) {
                        if (current.items.isNotEmpty()) {
                            // Someone is due, just not under these filters.
                            EmptyState(
                                systemImage = "bell-ring", title = L("Nothing here with these filters."),
                                actionTitle = L("Show everything"), action = { reason = "all"; bucket = null },
                                accent = Theme.pink, panel = true,
                            )
                        } else {
                            EmptyState(
                                systemImage = "bell-ring", title = L("Nobody is due in this window."),
                                message = L("Vaccines and rechecks come from follow-ups; grooming comes from each pet’s last visit and cadence."),
                                accent = Theme.pink, panel = true,
                            )
                        }
                    } else {
                        dueBuckets.forEach { key ->
                            val inBucket = items.filter { it.bucket == key }
                            if (inBucket.isNotEmpty()) BucketSection(key, inBucket)
                        }
                    }
                } else if (failure != null) {
                    ErrorState(message = failure) { load() }
                } else {
                    LoadingState()
                }
            }
        }
    }
    ReloadsOnScope { load() }
}

// MARK: Words and colours

/** A bucket's name. Several things are late, so "Overdue" reads in the plural here ("Vencidos"). */
private fun dueBucketTitle(key: String): String = when (key) {
    "overdue" -> L("Overdue@plural")
    "this_week" -> L("This week")
    else -> L("Later")
}

/** The bucket's state colour (web DUE_STATE): late red, this week mango, later light blue. */
@Composable
private fun dueBucketTone(key: String): Color = when (key) {
    "overdue" -> ProviderState.problem.color
    "this_week" -> ProviderState.waiting.color
    else -> ProviderState.upcoming.color
}

private fun dueReasonFilterLabel(key: String): String = when (key) {
    "all" -> L("All")
    "vaccine" -> L("Vaccines")
    "recheck" -> L("Rechecks")
    "grooming" -> L("Grooming")
    else -> L("Follow-ups")
}

private fun dueReasonLabel(key: String): String = when (key) {
    "vaccine" -> L("Vaccine")
    "recheck" -> L("Recheck")
    "grooming" -> L("Grooming")
    else -> L("Follow-up")
}

/** Each reason in its own hue, on the filter and on every row (web DUE_REASON_HUE). */
private fun dueReasonTone(key: String): Color = when (key) {
    "vaccine" -> Hue.sky.color
    "recheck" -> Hue.teal.color
    "grooming" -> Hue.lavender.color
    else -> Hue.periwinkle.color
}

private fun dueReasonIcon(key: String): String = when (key) {
    "grooming" -> "scissors"
    "vaccine" -> "syringe"
    "recheck" -> "stethoscope"
    else -> "bell-ring"
}
