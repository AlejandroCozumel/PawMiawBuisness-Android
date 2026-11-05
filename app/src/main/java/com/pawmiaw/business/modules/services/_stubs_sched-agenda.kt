package com.pawmiaw.business.services

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Stand-ins for the Services symbols the Agenda opens; deleted at merge.

// STUB: Modules/Services/GroupsSession.swift
@Composable
fun SvcSessionScreen(sessionId: String, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/GroupsSession.swift
@Composable
fun SvcSessionFormSheet(
    session: SvcSessionDetail?,
    people: List<SvcMember>,
    modifier: Modifier = Modifier,
    onSaved: (String) -> Unit,
) {}
