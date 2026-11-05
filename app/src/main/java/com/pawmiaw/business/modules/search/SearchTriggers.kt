package com.pawmiaw.business.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.pawmiaw.business.*
import com.pawmiaw.business.clients.ClientsCreateSheet

//
//  SearchTriggers.kt (iOS SearchTriggers.swift)
//
//  Where the search opens (web `ProviderSearchTrigger`), and where its choices go.
//  Phone: the top bar's round search button, before the bell; the palette fills
//  the screen and a choice is pushed on the page's own stack. Tablet (the docked
//  sidebar, where there is no top bar): the search bar under the business
//  switcher; the palette is a centred sheet, a page of the sidebar is chosen
//  there, and any other page (a client, a visit, a bill…) opens in a sheet with
//  its own navigation, as the place switcher's pages do.
//

/** The phone top bar's search (web `variant="icon"`): 36 round, 1 pt border, the magnifier at 16. */
@Composable
fun SearchTopBarButton(modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SearchDestination?>(null) }
    var creatingClient by remember { mutableStateOf(false) }
    var createdClientId by remember { mutableStateOf<String?>(null) }

    /** Once the palette has gone: push the page, or open the new-client sheet. */
    fun leave() {
        val destination = pending ?: return
        pending = null
        when (destination) {
            is SearchDestination.route -> nav.push(destination.route)
            SearchDestination.newClient -> creatingClient = true
            is SearchDestination.query -> {}
        }
    }

    IconCircleButton(systemImage = "search", label = L("Search"), tint = Theme.inkStrong, modifier = modifier) { open = true }

    SearchFullScreenCover(isPresented = open, onDismissRequest = { open = false }, onDismiss = { leave() }) {
        SearchScreen { destination ->
            pending = destination
            open = false
        }
    }

    if (creatingClient) {
        AppSheet(onDismissRequest = {
            creatingClient = false
            val id = createdClientId
            if (id != null) {
                createdClientId = null
                nav.push(ProviderRoute.client(id))
            }
        }) {
            ClientsCreateSheet { createdClientId = it.id }
        }
    }
}

/**
 * The docked sidebar's search bar (web `variant="bar"`): 36 tall, a capsule, the magnifier
 * and "Buscar pacientes, clientes…". Not drawn in the phone's slide-in sidebar (the top bar has it).
 */
@Composable
fun SearchSidebarField(
    /** The sidebar's own choice of page. */
    choose: (ProviderSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf<SearchSidebarSheet?>(null) }
    var pending by remember { mutableStateOf<SearchDestination?>(null) }
    var createdClientId by remember { mutableStateOf<String?>(null) }

    /** Once a sheet has gone: the next one, or the sidebar's page. */
    fun next() {
        val id = createdClientId
        if (id != null) {
            createdClientId = null
            shown = SearchSidebarSheet.route(ProviderRoute.client(id))
            return
        }
        val destination = pending ?: return
        pending = null
        when (destination) {
            is SearchDestination.route -> {
                val route = destination.route
                if (route is ProviderRoute.section) choose(route.section) else shown = SearchSidebarSheet.route(route)
            }
            SearchDestination.newClient -> shown = SearchSidebarSheet.newClient
            is SearchDestination.query -> {}
        }
    }

    // Whether this is the docked sidebar: the window is wide, as the shell reads it.
    if (LocalSizeClass.current != SizeClass.regular) return

    val capsule = RoundedCornerShape(50)
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .plainButton(label = L("Search")) { shown = SearchSidebarSheet.palette }
            .background(Theme.surface, capsule)
            .border(1.dp, Theme.borderSoft, capsule)
            .padding(start = 12.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("search", 16.dp, tint = Theme.inkSoft)
        Text(
            L("Search patients, clients…"),
            style = Theme.text(14),
            color = Theme.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }

    shown?.let { sheet ->
        key(sheet) {
            AppSheet(onDismissRequest = {
                shown = null
                next()
            }) {
                val dismiss = LocalDismiss.current
                when (sheet) {
                    SearchSidebarSheet.palette -> Box(Modifier.sheetPresentation()) {
                        SearchScreen { destination ->
                            pending = destination
                            dismiss()
                        }
                    }
                    is SearchSidebarSheet.route -> Box(Modifier.sheetPresentation()) {
                        // iOS: a NavigationStack with the page and its routes; its root's bar says "Close".
                        val navigator = remember { Navigator() }
                        NavStack(navigator = navigator, root = { RouteView(sheet.route) }, page = { RouteView(it) })
                    }
                    SearchSidebarSheet.newClient -> ClientsCreateSheet { createdClientId = it.id }
                }
            }
        }
    }
}

private sealed class SearchSidebarSheet {
    data object palette : SearchSidebarSheet()
    data class route(val route: ProviderRoute) : SearchSidebarSheet()
    data object newClient : SearchSidebarSheet()

    val id: SearchSidebarSheet get() = this
}

private val coverEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * SwiftUI `.fullScreenCover(isPresented:onDismiss:)`: the content fills the screen, sliding up
 * from the bottom over everything (no dim behind it) and down again; `onDismiss` runs once it
 * has gone. The system back gesture asks to close (`onDismissRequest`).
 */
@Composable
private fun SearchFullScreenCover(
    isPresented: Boolean,
    onDismissRequest: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    val visible = remember { MutableTransitionState(false) }
    val dismissed by rememberUpdatedState(onDismiss)
    LaunchedEffect(isPresented) {
        if (isPresented) {
            shown = true
            visible.targetState = true
        } else {
            visible.targetState = false
        }
    }
    if (!shown) return

    LaunchedEffect(visible.currentState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) {
            shown = false
            dismissed()
        }
    }

    val dark = LocalIsDark.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (window != null) {
                window.setDimAmount(0f)
                val bars = WindowCompat.getInsetsController(window, view)
                bars.isAppearanceLightStatusBars = !dark
                bars.isAppearanceLightNavigationBars = !dark
            }
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(tween(400, easing = coverEasing)) { it },
            exit = slideOutVertically(tween(320, easing = coverEasing)) { it },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Theme.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) { content() }
        }
    }
}
