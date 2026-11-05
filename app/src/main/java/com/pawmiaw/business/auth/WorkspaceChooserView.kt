package com.pawmiaw.business

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

//
//  A person who works in more than one business chooses one; a person with none is told where
//  a business is made (the web) or joined (an invitation). iOS WorkspaceChooserView.swift.
//

@Composable
fun WorkspaceChooserView(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val session = SessionStore.shared
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().background(Theme.canvas).statusBarsPadding()) {
        // The navigation stack's large title (iOS `.navigationTitle(L("Businesses"))`).
        Text(
            L("Businesses"),
            style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 34.sp),
            color = Theme.inkStrong,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).semantics { heading() },
        )
        Box(Modifier.weight(1f)) {
            PageScroll {
                Text(L("Which business are you working in?"), style = Theme.titleFont, color = Theme.inkStrong)
                workspace.workspaces.forEach { item ->
                    val source = remember { MutableInteractionSource() }
                    val pressed by source.collectIsPressedAsState()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = if (pressed) 0.6f else 1f }
                            .clip(RoundedCornerShape(Theme.cardRadius))
                            .clickable(interactionSource = source, indication = null, role = Role.Button) { scope.launch { workspace.choose(item) } }
                    ) {
                        Card {
                            ListRow(
                                title = item.name,
                                subtitle = WorkspaceRole.label(item.role),
                                detail = item.address,
                                showsChevron = true,
                                leading = { Avatar(name = item.name, imageURL = item.logoUrl, size = 48.dp) },
                            )
                        }
                    }
                }
                PMButton(L("Sign out"), variant = ButtonVariant.outline, fullWidth = true) { scope.launch { session.signOut() } }
            }
        }
    }
}

@Composable
fun NoWorkspaceView(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val session = SessionStore.shared
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()

    Box(modifier.fillMaxSize().background(Theme.canvas).statusBarsPadding()) {
        PageScroll(refresh = { workspace.load() }) {
            Column(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.business_mark),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)),
                )
                Text(L("You don't work in a business yet"), style = Theme.titleFont, color = Theme.inkStrong, textAlign = TextAlign.Center)
                Text(
                    L("Create your business on pawmiaw.com, or open the invitation your team sent to your email. Then come back here."),
                    style = Theme.bodyFont,
                    color = Theme.inkSoft,
                    textAlign = TextAlign.Center,
                )
                PMButton(L("Create my business"), systemImage = "safari", fullWidth = true) { openURL(APIConfig.webURL("providers").toString()) }
                PMButton(L("I already did: check again"), variant = ButtonVariant.outline, fullWidth = true) { scope.launch { workspace.load() } }
                PMButton(L("Sign out"), variant = ButtonVariant.outline, fullWidth = true) { scope.launch { session.signOut() } }
            }
        }
    }
}
