package com.pawmiaw.business.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant

//
//  HomeHomeVisits.kt (iOS HomeHomeVisits.swift)
//
//  Domicilio on Home, under its own tile (web today/home-hub.tsx
//  `HomeVisitsSection`): every home visit not done yet, in the order they
//  are made, with the one step it takes next — "Llegué", then "Terminar"
//  ("Salir" for a home stay's visit) — exactly as on the Home visits page,
//  whose pieces it uses. Everything else about a visit (the way there,
//  access, the report) is on that page.
//
//  Android: as in HomeRows.kt, the kit's `EdgeRow` keeps the time column
//  centred on the whole row (iOS moves it to the first line on a phone).
//

@Composable
fun HomeHomeVisitsSection(day: SvcHomeVisitsDay?, modifier: Modifier = Modifier) {
    val actions = LocalHomeActions.current
    val nav = LocalNavigator.current

    val openPage: (() -> Unit)? = actions?.let { a -> ({ a.route = ProviderRoute.section(ProviderSection.homeVisits) }) }

    if (day != null) {
        val items = day.items
        if (items.isEmpty()) {
            Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(systemImage = "house", title = L("No home visits today"), message = L("The day's visits to families' homes show up here."), accent = Theme.blue)
                PMButtonStyle(
                    ButtonVariant.link, ButtonSize.slim,
                    modifier = Modifier.homeHomeVisitsPullUp(12.dp).padding(bottom = 24.dp),
                    onClick = { nav.push(ProviderRoute.section(ProviderSection.homeVisits)) },
                ) {
                    PMButtonContent(title = L("Open home visits"), trailingIcon = "chevron-right", variant = ButtonVariant.link, size = ButtonSize.slim)
                }
            }
        } else {
            val open = items.filter { !it.closed }
            val done = items.count { it.phase == "done" }
            // The same remembered choice as Domicilio's cards under Todos: it is one section.
            HomeStrip(
                storageKey = "home", title = HomeActivity.home.label, count = items.size, dot = HomeActivity.home.accent,
                linkTitle = L("Open home visits"), link = openPage, modifier = modifier,
            ) {
                if (open.isEmpty()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("check-check", 16.dp, tint = Theme.blue)
                        Text(L("Every home visit today is done."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
                    }
                } else {
                    // `TimelineView(.periodic(from: .now, by: 30))`: "late" and "running long" follow the clock.
                    var now by remember { mutableStateOf(Instant.now()) }
                    LaunchedEffect(Unit) {
                        while (isActive) {
                            delay(30_000)
                            now = Instant.now()
                        }
                    }
                    RowList(items = open.take(HomeCap.rows * 2)) { item ->
                        HomeHomeVisitRow(item = item, day = day, now = now)
                    }
                    if (open.size > HomeCap.rows * 2) {
                        HomeStripMore(more = open.size - HomeCap.rows * 2, route = ProviderRoute.section(ProviderSection.homeVisits))
                    }
                    // The count sits under the list.
                    if (done > 0) {
                        RowDivider()
                        Text(
                            LF("%d of %d done", done, items.size),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = Theme.text(12, FontWeight.Bold),
                            color = Theme.inkSoft,
                        )
                    }
                }
            }
        }
    } else {
        LoadingState(modifier = modifier)
    }
}

@Composable
private fun HomeHomeVisitRow(item: SvcHomeVisitItem, day: SvcHomeVisitsDay, now: Instant) {
    val actions = LocalHomeActions.current
    val nav = LocalNavigator.current

    val phone = LocalSizeClass.current != SizeClass.regular
    val lateAfter = day.settings?.lateAfterMinutes ?: 15
    val late = SvcHomeRules.late(item, now = now, lateAfter = lateAfter, isToday = day.isToday ?: false)
    val runningLong = SvcHomeRules.runningLong(item, now = now, lateAfter = lateAfter)

    /** The row's one word and its tone (lib/home-visits.ts `visitChip`). */
    val chip: Pair<String, Chip.Tone>? = if (late.first) LF("Late · %d min", late.second) to Chip.Tone.danger else when (item.phase) {
        "on_my_way" -> L("On the way") to Chip.Tone.info
        "in_progress" -> if (runningLong) L("Running long") to Chip.Tone.warn else L("In progress") to Chip.Tone.info
        else -> null
    }

    /** The edge's colour (home-hub.tsx `HOME_TONE`). */
    val tone: Color = if (late.first) Theme.destructive else when (item.phase) {
        "in_progress" -> if (runningLong) Theme.mango else Theme.blue
        "done" -> Theme.inkSoft
        "missed" -> Theme.destructive
        else -> Theme.aqua
    }

    val hasSteps = actions != null && (item.actions?.arrive == true || item.actions?.finish == true)

    fun openPage() {
        actions?.route = ProviderRoute.section(ProviderSection.homeVisits)
    }

    val steps: @Composable () -> Unit = {
        if (actions != null) {
            val busy = actions.isBusy(item.id)
            Row(if (phone) Modifier.fillMaxWidth() else Modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // A phone: the buttons share the line.
                val each = if (phone) Modifier.weight(1f) else Modifier
                if (item.actions?.arrive == true) {
                    PMButton(L("I’ve arrived"), systemImage = "door-open", size = ButtonSize.compact, fullWidth = phone, isWorking = busy, modifier = each) { actions.arriveHome(item) }
                }
                if (item.actions?.finish == true) {
                    val id = item.appointmentId
                    if (item.isStay || SvcHomeRules.finishesHere(item.serviceCategory)) {
                        PMButton(
                            if (item.isStay) L("Leave@home") else L("Finish@visit"), systemImage = if (item.isStay) "log-out" else "badge-check",
                            size = ButtonSize.compact, fullWidth = phone, isWorking = busy, modifier = each,
                        ) {
                            actions.finishHome(item, timeZone = day.timeZone, canWrite = day.viewer?.canAct ?: false)
                        }
                    } else if (id != null) {
                        PMButtonStyle(ButtonVariant.outline, ButtonSize.compact, fullWidth = phone, modifier = each, onClick = { nav.push(ProviderRoute.appointment(id)) }) {
                            PMButtonContent(title = L("Open visit"), icon = "external-link")
                        }
                    }
                }
            }
        }
    }

    val openLabel = L("Open home visits")
    EdgeRow(
        edge = tone,
        time = DateText.time(item.startsAt),
        // The row opens the Home visits page; its buttons take the next step.
        modifier = Modifier
            .plainButton { openPage() }
            .semantics { customActions = listOf(CustomAccessibilityAction(openLabel) { openPage(); true }) },
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.pets.size > 1) {
                    PetStack(pets = item.pets.map { Triple(it.name, it.species, it.photoUrl) }, size = 40.dp)
                } else {
                    PetAvatar(name = item.pets.firstOrNull()?.name ?: item.petNames, species = item.pets.firstOrNull()?.species, photoURL = item.pets.firstOrNull()?.photoUrl, size = 40.dp)
                }
                // The details.
                Column(Modifier.weight(1f)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(Theme.text(16, FontWeight.Bold).toSpanStyle().copy(color = Theme.inkStrong)) { append(item.petNames) }
                            withStyle(Theme.text(16).toSpanStyle().copy(color = Theme.inkSoft)) { append(" · " + item.serviceText) }
                        },
                        style = Theme.text(16),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOf(item.client.name, item.address?.line, item.assigned?.name?.let { LF("with %@", it) })
                        .mapNotNull { it.nonEmpty }.joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Text(details, Modifier.padding(top = 2.dp), style = Theme.text(12.8), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (chip != null) {
                        FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 6.dp)) { Chip(text = chip.first, tone = chip.second) }
                    }
                }
                if (!phone && hasSteps) steps()
            }
            if (phone && hasSteps) steps()
        }
    }
}

/** SwiftUI `.padding(.top, -x)`: drawn `x` higher, its frame `x` shorter at the top. */
private fun Modifier.homeHomeVisitsPullUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val px = amount.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) { placeable.place(0, -px) }
}
