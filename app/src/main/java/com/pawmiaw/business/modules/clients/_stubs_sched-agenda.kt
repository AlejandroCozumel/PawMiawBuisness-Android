package com.pawmiaw.business.clients

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Stand-ins for the Clients symbols the Schedule's booking uses; deleted at merge.

// STUB: Modules/Clients/ClientsClientSections.swift
@Composable
fun ClientsAddressSheet(
    clientId: String,
    address: ClientsAddress?,
    makeDefault: Boolean,
    modifier: Modifier = Modifier,
    onSaved: suspend () -> Unit,
) {}
