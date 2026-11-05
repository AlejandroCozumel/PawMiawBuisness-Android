package com.pawmiaw.business.team

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for the Team symbols the `team-settings` job ports (Settings, hours, Olfato AI
//  settings). Deleted at merge.
//

// STUB: Modules/Team/SettingsScreen.swift
@Composable
fun TeamSettingsSectionScreen(section: String, modifier: Modifier = Modifier) {}

// STUB: Modules/Team/TeamOlfatoSettings.swift
@Composable
fun TeamOlfatoSettingsPage(modifier: Modifier = Modifier) {}

// STUB: Modules/Team/TeamHours.swift
object TeamHours {
    fun ranges(day: TeamDay?): List<TeamTimeRange> = TODO()
    fun ordered(schedule: List<TeamDay>): List<TeamDay> = TODO()
    fun body(schedule: List<TeamDay>): List<Map<String, Any?>> = TODO()
    fun minuteRanges(ranges: List<TeamTimeRange>): List<Pair<Int, Int>> = TODO()
    fun minutes(ranges: List<TeamTimeRange>): Int = TODO()
    fun intersect(a: List<Pair<Int, Int>>, b: List<Pair<Int, Int>>): List<Pair<Int, Int>> = TODO()
    fun dayIsValid(day: TeamDay): Boolean = TODO()
    fun sameWeek(a: List<TeamDay>, b: List<TeamDay>): Boolean = TODO()
    fun dayShort(key: String): String = TODO()
}

// STUB: Modules/Team/TeamHours.swift
@Composable
fun TeamWeekEditor(
    schedule: MutableState<List<TeamDay>>,
    guide: List<TeamDay>? = null,
    disabled: Boolean = false,
    closedHint: String,
    openWord: String = L("Open"),
    closedWord: String = L("Closed"),
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Team/TeamHours.swift
@Composable
fun TeamWeekList(week: List<TeamDay>, closedWord: String = L("Day off"), modifier: Modifier = Modifier) {}
