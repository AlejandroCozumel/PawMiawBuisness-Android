package com.pawmiaw.business

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.pawmiaw.business.places.PlacesSwitcherMenu
import com.pawmiaw.business.search.SearchSidebarField
import com.pawmiaw.business.search.SearchTopBarButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.WeakHashMap

//
//  The workspace's frame, as the web draws it (provider-shell.tsx; iOS AppShell.swift). On a
//  phone: one white top bar (the sidebar button, the PawMiaw wordmark, the place, the search,
//  the bell) and the sidebar sliding in from the left with every page, grouped OPERACIÓN ·
//  COBROS · NEGOCIO; there is no tab bar. On a tablet the same sidebar stays docked beside the page.
//
//  A page wraps itself in `providerPage(title)`: a section's own page sits under the shell's top
//  bar; a page pushed over another (or the root of a sheet's stack, or any page on a tablet)
//  gets the small bar with its title, and a back arrow naming the page under it (iOS's inline
//  navigation bar).
//

private val shellEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun AppShell(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val badges = remember { Badges() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Every minute while the app is in front, and at once when the business or the place changes.
    LaunchedEffect(workspace.scopeVersion, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                badges.refresh(workspace)
                delay(60_000)
            }
        }
    }
    LaunchedEffect(Unit) {
        AppEvents.dataChanged.collect { launch { badges.refresh(workspace) } }
    }
    CompositionLocalProvider(LocalBadges provides badges) {
        Box(modifier.fillMaxSize()) {
            if (LocalSizeClass.current == SizeClass.regular) SidebarShell() else PhoneShell()
        }
    }
}

// MARK: - Opening the sidebar

/** Slides the sidebar in (phone); its `action` is null where the sidebar is docked (iOS `\.openSidebar`). */
class OpenSidebarAction(val action: (() -> Unit)?) {
    operator fun invoke() {
        action?.invoke()
    }
}

/** iOS `@Environment(\.openSidebar)`. */
val LocalOpenSidebar = compositionLocalOf { OpenSidebarAction(null) }

// MARK: - Phone

@Composable
private fun PhoneShell() {
    var section by remember { mutableStateOf(ProviderSection.launchSection) }
    var open by remember { mutableStateOf(false) }
    val openAction = remember { OpenSidebarAction { open = true } }
    val dark = LocalIsDark.current

    Box(Modifier.fillMaxSize()) {
        // A new section starts its own stack (iOS `.id(section)`).
        key(section) {
            val navigator = remember { Navigator() }
            CompositionLocalProvider(LocalOpenSidebar provides openAction) {
                NavStack(
                    navigator = navigator,
                    root = {
                        Column(Modifier.fillMaxSize().background(Theme.canvas)) {
                            ProviderTopBar()
                            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.statusBars)) {
                                SectionView(section = section)
                            }
                        }
                    },
                    page = { RouteView(it) },
                )
            }
        }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(300, easing = shellEasing)),
            exit = fadeOut(tween(300, easing = shellEasing)),
        ) {
            // iOS: black at 10% over a thin material; the frosting is a pale (or dark) veil here.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (dark) Color.Black.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.18f))
                    .background(Color.Black.copy(alpha = 0.1f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = false }
                    .semantics { contentDescription = L("Close") }
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(300, easing = shellEasing)) { -it },
            exit = slideOutHorizontally(tween(300, easing = shellEasing)) { -it },
        ) {
            val dragLimit = with(LocalDensity.current) { 60.dp.toPx() }
            Box(
                Modifier
                    .width(288.dp)
                    .fillMaxHeight()
                    .shadow(24.dp, RectangleShape, clip = false, ambientColor = Theme.navy.copy(alpha = 0.14f), spotColor = Theme.navy.copy(alpha = 0.14f))
                    .background(Theme.sidebarBackground)
                    .pointerInput(Unit) {
                        var travelled = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { travelled = 0f },
                            onDragEnd = { if (travelled < -dragLimit) open = false },
                        ) { _, dx -> travelled += dx }
                    }
            ) {
                SidebarPanel(
                    selection = section,
                    choose = { chosen ->
                        section = chosen
                        open = false
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
                )
                Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(Theme.borderSoft))
            }
        }
        BackHandler(enabled = open) { open = false }
    }
}

/**
 * The web's phone top bar (56 tall, white, a hairline under it): the sidebar button, the
 * wordmark, the place when there are several, the search, the bell.
 */
@Composable
fun ProviderTopBar(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val badges = LocalBadges.current
    val openSidebar = LocalOpenSidebar.current
    val navigator = LocalNavigator.current
    val unread = badges.unreadNotifications

    Box(
        modifier
            .fillMaxWidth()
            .background(Theme.surface)
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
            .height(56.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (openSidebar.action != null) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Theme.surface)
                        .border(1.dp, Theme.borderSoft, CircleShape)
                        .pressable(label = L("Menu")) { openSidebar() },
                    contentAlignment = Alignment.Center,
                ) { Glyph("panel-left", 16.dp, tint = Theme.inkStrong) }
            }
            Wordmark(height = 26.dp)
            Spacer(Modifier.weight(1f))
            if (workspace.multiLocation) PlacesSwitcherMenu()
            SearchTopBarButton()
            Box(
                Modifier
                    .size(36.dp)
                    .pressable(label = L("Notifications")) { navigator.push(ProviderRoute.section(ProviderSection.notifications)) }
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(Theme.surface)
                        .border(1.dp, if (unread > 0) Theme.coral else Theme.borderSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Glyph("bell", 20.dp, tint = Theme.inkSoft) }
                if (unread > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-6).dp)
                            .border(1.5.dp, Theme.surface, RoundedCornerShape(50))
                    ) { CountBadge(count = unread, color = Theme.coral, cap = 9) }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Theme.borderSoft))
    }
}

/** The PawMiaw wordmark at a height, its width from the artwork. */
@Composable
private fun Wordmark(height: Dp, modifier: Modifier = Modifier) {
    val painter = painterResource(R.drawable.wordmark)
    val size = painter.intrinsicSize
    val ratio = if (size.isSpecified && size.height > 0f) size.width / size.height else 4f
    Image(
        painter = painter,
        contentDescription = "PawMiaw",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height).aspectRatio(ratio),
    )
}

// MARK: - The sidebar

/**
 * The web's sidebar (provider-shell.tsx): the business switcher, the pages in three groups with
 * their numbers, and the person at the foot.
 */
@Composable
fun SidebarPanel(selection: ProviderSection?, choose: (ProviderSection) -> Unit, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val badges = LocalBadges.current

    Column(modifier) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { PlacesSwitcherMenu(sidebar = true) }
                SearchSidebarField(choose = choose)
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Theme.sidebarBorder))
        }
        val groups = SectionGroup.entries
            .map { group -> group to group.sections.filter { it.isVisible(workspace, olfatoShown = badges.olfatoShown, pharmacyShown = badges.pharmacyShown) } }
            .filter { it.second.isNotEmpty() }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            groups.forEachIndexed { index, (group, sections) ->
                if (index > 0) {
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(Theme.sidebarBorder))
                }
                Box(Modifier.height(32.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        group.title.uppercase(appLocale),
                        style = Theme.text(10.9, FontWeight.ExtraBold).copy(letterSpacing = 1.75.sp),
                        color = Theme.inkSoft,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sections.forEach { section ->
                        SidebarItem(section = section, count = badges.count(section), selected = section == selection) { choose(section) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth()) {
            SidebarAccountRow(choose = choose, modifier = Modifier.padding(12.dp))
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp).background(Theme.sidebarBorder))
        }
    }
}

/**
 * One page in the sidebar: 32 tall, icon 16, label 14; chosen = PAW Blue with a gold edge and
 * white bold words; its number in a red capsule.
 */
@Composable
private fun SidebarItem(section: ProviderSection, count: Int, selected: Boolean, action: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val glow = Theme.blueFixed.copy(alpha = 0.14f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(
                if (selected) Modifier
                    .shadow(11.dp, shape, clip = false, ambientColor = glow, spotColor = glow)
                    .background(Theme.sidebarActive, shape)
                    .border(2.dp, Theme.sidebarActiveBorder, shape)
                else Modifier
            )
            .clip(shape)
            .pressable(label = null) { action() }
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (selected) Theme.sidebarActiveInk else Theme.inkSoft) {
            SectionIcon(section = section, size = 16.dp)
            Text(
                section.title,
                style = Theme.text(14, if (selected) FontWeight.Bold else FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CountBadge(count = count)
        }
    }
}

/** A section's icon: the web's Lucide icon (Olfato AI wears its corgi). */
@Composable
fun SectionIcon(section: ProviderSection, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    if (section == ProviderSection.olfato) {
        Image(
            painter = rememberAsyncImagePainter("file:///android_asset/img/OlfatoFace.svg"),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size + 2.dp),
        )
    } else {
        Glyph(section.icon, size, modifier = modifier)
    }
}

/** The person at the foot of the sidebar, opening the account menu. */
@Composable
private fun SidebarAccountRow(choose: (ProviderSection) -> Unit, modifier: Modifier = Modifier) {
    val session = SessionStore.shared
    val user = session.user
    val theme = ThemeChoice.theme
    var confirmSignOut by remember { mutableStateOf(false) }
    val box = RoundedCornerShape(Theme.boxRadius)

    AppMenu(
        modifier = modifier,
        content = {
            if (user != null) {
                section(user.email)
                item(L("Your account"), icon = "user-round") { choose(ProviderSection.accountSecurity) }
                item(L("Notification settings"), icon = "bell-ring") { choose(ProviderSection.notificationSettings) }
                divider()
            }
            // iOS `.pickerStyle(.menu)`: a submenu with the choices.
            picker(
                title = L("Theme"),
                icon = if (theme == "dark") "moon" else "sun",
                options = listOf("light" to L("Light"), "dark" to L("Dark")),
                selection = stateOf({ ThemeChoice.theme }, { ThemeChoice.set(it) }),
                inline = false,
            )
            picker(
                title = L("Language"),
                icon = "languages",
                options = AppLanguage.entries.map { it to it.displayName },
                selection = stateOf({ LocalizationManager.currentLanguage }, { LocalizationManager.setLanguage(it) }),
                inline = false,
            )
            item(L("Sign out"), icon = "log-out", destructive = true) { confirmSignOut = true }
        },
        label = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.sidebarControl, box)
                    .border(1.dp, Theme.sidebarControlBorder, box)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccountAvatar(name = user?.name, imageURL = user?.image, size = 28.dp)
                Text(
                    NameText.firstName(user?.name),
                    style = Theme.text(14, FontWeight.SemiBold),
                    color = Theme.inkStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
    if (confirmSignOut) {
        AppSheet(onDismissRequest = { confirmSignOut = false }) {
            ConfirmSheet(
                title = L("Sign out of PawMiaw Business?"),
                confirmTitle = L("Sign out"),
                destructive = true,
                action = { session.signOut() },
            )
        }
    }
}

/** The account's face: the photo, or white initials on PAW Blue. */
@Composable
fun AccountAvatar(name: String?, imageURL: String? = null, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Theme.blueFixed), contentAlignment = Alignment.Center) {
        Text(NameText.initials(name), style = Theme.text(size.value * 0.36f, FontWeight.Bold), color = Color.White)
        APIConfig.assetURL(imageURL)?.let { url ->
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().clip(CircleShape))
        }
    }
}

/** The business's mark: its logo on white, or its initials on PAW Blue. */
@Composable
fun BusinessMarkView(name: String, logoURL: String? = null, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val url = APIConfig.assetURL(logoURL)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (url != null) Color.White else Theme.blueFixed)
            .border(if (logoURL == null) 0.dp else 1.dp, if (logoURL == null) Color.Transparent else Theme.borderSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize().padding(2.dp).clip(CircleShape))
        } else {
            Text(NameText.initials(name), style = Theme.text(12, FontWeight.ExtraBold), color = Color.White)
        }
    }
}

/**
 * The place this device works at, on a page's toolbar: the business's one switcher
 * (`PlacesSwitcherMenu`), shown when there is more than one place.
 */
@Composable
fun LocationMenu(modifier: Modifier = Modifier) {
    if (Workspace.shared.multiLocation) PlacesSwitcherMenu(modifier = modifier)
}

// MARK: - Tablet

@Composable
private fun SidebarShell() {
    var selection by remember { mutableStateOf(ProviderSection.launchSection) }
    Row(Modifier.fillMaxSize().background(Theme.canvas)) {
        SidebarPanel(
            selection = selection,
            choose = { selection = it },
            modifier = Modifier
                .width(256.dp)
                .fillMaxHeight()
                .background(Theme.sidebarBackground)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.borderSoft))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // Choosing a page starts its own stack (iOS `.id(selection)` and a fresh path).
            key(selection) {
                val navigator = remember { Navigator() }
                NavStack(navigator = navigator, root = { SectionView(section = selection) }, page = { RouteView(it) })
            }
        }
    }
}

// MARK: - Page bar

/** Where a page sits: its stack and how deep (0 = the stack's root). `RouteView` sets it. */
class PageSlot(val navigator: Navigator?, val depth: Int)

val LocalPageSlot = compositionLocalOf { PageSlot(null, 0) }

/** The bar of the page a view is in (`PageBackHandler` reaches it). */
@Stable
class PageBarState {
    var backOverride: (() -> Unit)? by mutableStateOf(null)
}

val LocalPageBar = compositionLocalOf<PageBarState?> { null }

/**
 * A page that must ask before it is left (iOS `.navigationBarBackButtonHidden(true)` with its own
 * leading Back, TeamLeaveGuard): while [enabled], the bar's back arrow and the system back
 * gesture call [onBack] instead of leaving.
 */
@Composable
fun PageBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val bar = LocalPageBar.current
    val current by rememberUpdatedState(onBack)
    DisposableEffect(bar, enabled) {
        val action: () -> Unit = { current() }
        if (enabled) bar?.backOverride = action
        onDispose { if (bar?.backOverride === action) bar.backOverride = null }
    }
    BackHandler(enabled = enabled) { current() }
}

/**
 * The titles of the pages on each stack, by depth: a pushed page's back arrow names the one under
 * it. Each page adds its own entry and takes only that one away, so two pages at one depth for a
 * moment (a page in a sheet over the stack) never wipe each other's title.
 */
private object PageTitles {
    class Entry(val owner: Any, val title: String)

    private val stacks = WeakHashMap<Navigator, SnapshotStateMap<Int, List<Entry>>>()

    fun of(navigator: Navigator): SnapshotStateMap<Int, List<Entry>> = stacks.getOrPut(navigator) { mutableStateMapOf() }

    fun SnapshotStateMap<Int, List<Entry>>.add(depth: Int, owner: Any, title: String) {
        this[depth] = (this[depth] ?: emptyList()).filter { it.owner !== owner } + Entry(owner, title)
    }

    fun SnapshotStateMap<Int, List<Entry>>.drop(depth: Int, owner: Any) {
        val left = (this[depth] ?: return).filter { it.owner !== owner }
        if (left.isEmpty()) remove(depth) else this[depth] = left
    }

    fun SnapshotStateMap<Int, List<Entry>>.title(depth: Int): String? = this[depth]?.lastOrNull()?.title
}

/**
 * A page's frame (iOS `.providerPage(title, root:)`): no big title (the module header carries
 * it). A section's own page sits under the shell's top bar; a page opened from another shows a
 * small bar with its back arrow and its title, the root of a sheet's stack its title and
 * "Close", and on a tablet every page its title. `root` is the Swift parameter, kept for the
 * call sites: the bar follows where the page is, not the flag.
 */
@Composable
fun providerPage(
    title: String,
    root: Boolean = false,
    /**
     * iOS `.navigationBarBackButtonHidden(true)` on a page whose content is a ProviderModal opened
     * as the page (booking, a new record): no bar at all — the modal's tinted header is the page's
     * header and its Cancel (or the back gesture, which asks first when something changed) leaves.
     */
    hidesBackButton: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val navigator = LocalNavigator.current
    val slot = LocalPageSlot.current
    val depth = if (slot.navigator === navigator) slot.depth else 0
    val titles = remember(navigator) { PageTitles.of(navigator) }
    val owner = remember { Any() }
    DisposableEffect(titles, depth, title) {
        with(PageTitles) { titles.add(depth, owner, title) }
        onDispose { with(PageTitles) { titles.drop(depth, owner) } }
    }
    val pushed = LocalIsPushedPage.current
    val inSheet = LocalAppSheet.current != null
    val docked = LocalSizeClass.current == SizeClass.regular
    val dismiss = LocalDismiss.current
    val bar = remember { PageBarState() }
    val showsBar = (pushed || inSheet || docked) && !(hidesBackButton && !inSheet)

    Column(modifier.fillMaxSize().background(Theme.canvas)) {
        if (showsBar) {
            ProviderPageBar(
                title = title,
                leading = when {
                    pushed -> PageBarLeading.back
                    inSheet -> PageBarLeading.close
                    else -> PageBarLeading.none
                },
                backTitle = if (depth > 0) with(PageTitles) { titles.title(depth - 1) } else null,
                statusBar = !inSheet,
                onLeading = { bar.backOverride?.invoke() ?: dismiss() },
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (showsBar && !inSheet) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)
        ) {
            CompositionLocalProvider(LocalPageBar provides bar) { content() }
        }
    }
}

private enum class PageBarLeading { none, back, close }

/**
 * iOS's inline navigation bar: 44 tall on the canvas, the title centred (17 semibold, the
 * system font), the back arrow with the page under it named in PAW Blue — "Back" when that
 * name doesn't fit, the arrow alone when neither does.
 */
@Composable
private fun ProviderPageBar(title: String, leading: PageBarLeading, backTitle: String?, statusBar: Boolean, onLeading: () -> Unit) {
    val titleStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Theme.inkStrong)
    val buttonStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 17.sp, color = Theme.blue)
    val backWord = L("Back")
    Box(
        Modifier
            .fillMaxWidth()
            .background(Theme.canvas)
            .then(if (statusBar) Modifier.statusBarsPadding() else Modifier)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
            .height(44.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val full = constraints.maxWidth
            val margin = with(density) { 8.dp.roundToPx() }
            val arrow = with(density) { 24.dp.roundToPx() }
            val gap = with(density) { 12.dp.roundToPx() }
            val titleWidth = measurer.measure(title, titleStyle, maxLines = 1).size.width
            val titleMax = (if (leading == PageBarLeading.none) full - 2 * with(density) { 16.dp.roundToPx() } else full - 2 * (margin + arrow + gap)).coerceAtLeast(0)
            val room = (full - minOf(titleWidth, titleMax)) / 2 - margin - arrow - gap
            val backLabel = if (leading == PageBarLeading.back) {
                listOfNotNull(backTitle, backWord).firstOrNull { measurer.measure(it, buttonStyle, maxLines = 1).size.width <= room }
            } else null

            Text(
                title,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = with(density) { titleMax.toDp() })
                    .semantics { heading() },
            )
            when (leading) {
                PageBarLeading.back -> Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                        .heightIn(min = 44.dp)
                        .pressable(label = if (backLabel == null) backWord else null) { onLeading() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("chevron-left", 24.dp, tint = Theme.blue)
                    if (backLabel != null) Text(backLabel, style = buttonStyle, maxLines = 1)
                }
                PageBarLeading.close -> Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp)
                        .heightIn(min = 44.dp)
                        .pressable(label = null) { onLeading() },
                    contentAlignment = Alignment.Center,
                ) { Text(L("Close"), style = buttonStyle) }
                PageBarLeading.none -> {}
            }
        }
    }
}

/** SwiftUI `.buttonStyle(.plain)`: the label dims while pressed, no ripple. */
private fun Modifier.pressable(label: String?, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    this
        .graphicsLayer { alpha = if (pressed) 0.55f else 1f }
        .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
}
