package com.pawmiaw.business.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Inbox (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Inbox/InboxOlfatoScreens.swift
@Composable
fun OlfatoChatScreen(chatId: String, modifier: Modifier = Modifier) = ShellStub("OlfatoChatScreen", modifier)

// STUB: Modules/Inbox/InboxOlfatoPetChat.swift
@Composable
fun OlfatoPetChatScreen(petId: String, modifier: Modifier = Modifier) = ShellStub("OlfatoPetChatScreen", modifier)

// STUB: Modules/Inbox/InboxOlfatoScreens.swift
@Composable
fun OlfatoScreen(modifier: Modifier = Modifier) = ShellStub("OlfatoScreen", modifier)

// STUB: Modules/Inbox/InboxNotifications.swift
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier) = ShellStub("NotificationsScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
