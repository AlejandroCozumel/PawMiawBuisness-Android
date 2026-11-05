package com.pawmiaw.business.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil.compose.rememberAsyncImagePainter
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

//
//  InboxOlfatoScreens.kt (iOS InboxOlfatoScreens.swift)
//
//  Olfato AI for businesses (web routes/provider.olfato*.tsx,
//  olfato-business-page.tsx, -gates.tsx, olfato-chat-list.tsx,
//  olfato-chat-dialogs.tsx): the person's chats, newest first by day, 20 at
//  a time; a chat; a new chat. The notice is the whole page until someone
//  who edits the workspace confirms it; a support session, the person's
//  access, the switch and the server each say why nothing can be asked,
//  and earlier chats stay readable then. Chats are private to whoever asked.
//

// MARK: - The chats

@Stable
class InboxOlfatoChatListModel(
    /** One way in ("business" or "pet") and one pet's chats; null lists every chat of the person. */
    private val way: String? = null,
    private val petId: String? = null,
) {
    var chats: List<InboxOlfatoChat> by mutableStateOf(emptyList())
        private set
    var ready: Boolean by mutableStateOf(false)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set
    var loadingMore: Boolean by mutableStateOf(false)
        private set
    private var cursor: String? by mutableStateOf(null)
    val hasMore: Boolean get() = cursor != null

    private fun query(cursor: String? = null): Map<String, String?> =
        mapOf("limit" to "20", "way" to way, "petId" to petId, "cursor" to cursor)

    suspend fun load() {
        try {
            val page = API.get<InboxOlfatoChatList>("/provider-olfato/chats", query())
            val fresh = page.chats ?: emptyList()
            val freshIds = fresh.map { it.id }.toSet()
            val oldest = fresh.lastOrNull()?.sortDate ?: Instant.MAX
            // The newest page, then older ones already shown.
            chats = fresh + chats.filter { it.id !in freshIds && it.sortDate <= oldest }
            if (cursor == null || chats.size <= fresh.size) cursor = page.nextCursor
            failed = false
        } catch (cancelled: CancellationException) {
            return
        } catch (failure: Throwable) {
            failed = true
        }
        ready = true
    }

    suspend fun loadMore() {
        val cursor = cursor ?: return
        if (loadingMore) return
        loadingMore = true
        try {
            val page = API.get<InboxOlfatoChatList>("/provider-olfato/chats", query(cursor))
            val known = chats.map { it.id }.toSet()
            chats = chats + (page.chats ?: emptyList()).filter { it.id !in known }
            this.cursor = page.nextCursor
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failed = true
        } finally {
            loadingMore = false
        }
    }

    fun replace(chat: InboxOlfatoChat) {
        val index = chats.indexOfFirst { it.id == chat.id }
        if (index >= 0) chats = chats.toMutableList().also { it[index] = chat }
    }

    fun remove(id: String) {
        chats = chats.filterNot { it.id == id }
    }
}

/** Olfato AI (`/provider/olfato`): a chat not begun, with "Tus chats" a tap away. */
@Composable
fun OlfatoScreen(modifier: Modifier = Modifier) {
    InboxOlfatoPage(chatId = null, root = true, modifier = modifier)
}

/** One of the person's chats, by its id (`/provider/olfato/<id>`). */
@Composable
fun OlfatoChatScreen(chatId: String, modifier: Modifier = Modifier) {
    InboxOlfatoPage(chatId = chatId, root = false, modifier = modifier)
}

/**
 * Olfato AI's artwork (iOS `Image("OlfatoAI")` / `Image("OlfatoFace")`): the corgi in a vet coat
 * and its face, from the app's assets, scaled to fit. Never read out.
 */
@Composable
fun InboxOlfatoArtwork(asset: String, modifier: Modifier = Modifier) {
    Image(
        painter = rememberAsyncImagePainter("file:///android_asset/img/$asset.svg"),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/**
 * iOS `.onAppear` on a page that comes back on top of its stack: runs when a page pushed over this
 * one is popped (the first appear is the page's own load).
 */
@Composable
private fun InboxOnReturn(action: () -> Unit) {
    val navigator = LocalNavigator.current
    val current by rememberUpdatedState(action)
    val depth = remember(navigator) { navigator.depth }
    LaunchedEffect(navigator) {
        var covered = false
        snapshotFlow { navigator.depth }.collect { now ->
            if (now > depth) {
                covered = true
            } else if (covered) {
                covered = false
                current()
            }
        }
    }
}

/**
 * The web's one Olfato AI page (olfato-business-page.tsx): no module header, a 56 dp chat bar (the
 * vet-coat corgi, the chat's name, what it is about, its ⋯ and "Nuevo chat"), the open chat with
 * the rest of the screen, and the person's chats in a panel from the left ("Tus chats" — the web's
 * sidebar, a sheet of its own outside the shell). Opening another chat swaps it in place; a reply
 * still being written is finished and kept on the server. The notice, a support session and a
 * closed workspace are the whole page; earlier chats stay readable when nothing new can be asked.
 */
@Composable
fun InboxOlfatoPage(chatId: String?, root: Boolean, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    val viewer = remember { InboxOlfatoViewer() }
    val list = remember { InboxOlfatoChatListModel() }
    var model by remember { mutableStateOf(InboxOlfatoChatModel(chatId)) }
    val listOpen = remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<InboxOlfatoChat?>(null) }
    var deleting by remember { mutableStateOf<InboxOlfatoChat?>(null) }
    var loadedOnce by remember { mutableStateOf(false) }

    val gate = viewer.gate
    val canEditWorkspace = workspace.can(Permission.workspaceEdit)
    val begun = model.chat != null || model.messages.isNotEmpty() || model.gone

    // MARK: Moving between chats

    fun open(id: String) {
        if (id == model.chatId) return
        // Only stops watching: a reply still being written is finished and kept on the server.
        model.leave()
        model = InboxOlfatoChatModel(chatId = id)
    }

    fun startNew() {
        if (!begun) return
        model.leave()
        model = InboxOlfatoChatModel(chatId = null)
    }

    suspend fun rename(chat: InboxOlfatoChat, title: String) {
        if (chat.id == model.chat?.id) {
            model.rename(title)
            model.chat?.let { list.replace(it) }
        } else {
            val envelope = API.patch<InboxOlfatoChatEnvelope>("/provider-olfato/chats/${chat.id}", mapOf("title" to title))
            list.replace(envelope.chat)
        }
    }

    suspend fun delete(chat: InboxOlfatoChat) {
        if (chat.id == model.chat?.id) {
            model.delete()
            list.remove(chat.id)
            // The open chat deleted: a chat not begun takes its place.
            model = InboxOlfatoChatModel(chatId = null)
        } else {
            try {
                API.send("DELETE", "/provider-olfato/chats/${chat.id}")
            } catch (error: APIError) {
                // Gone already is what was asked for.
                if (!(error.code == "olfato_chat_not_found" || error.status == 404)) throw error
            }
            list.remove(chat.id)
        }
    }

    providerPage(if (root) "Olfato AI" else model.title, root = root, modifier = modifier) {
        when (gate) {
            InboxOlfatoGate.loading -> PageScroll { LoadingState(text = L("Loading Olfato AI")) }
            InboxOlfatoGate.notice -> InboxOlfatoNoticeGate(canConfirm = canEditWorkspace) {
                viewer.load()
                list.load()
            }
            InboxOlfatoGate.privateSession, InboxOlfatoGate.error ->
                InboxOlfatoGatePage(gate = gate, canEditWorkspace = canEditWorkspace) { viewer.load() }
            else -> {
                if (gate != InboxOlfatoGate.ready && list.ready && list.chats.isEmpty() && model.chatId == null) {
                    // Nothing can be asked, and nothing earlier to read: the reason is the page.
                    InboxOlfatoGatePage(gate = gate, canEditWorkspace = canEditWorkspace, retry = null)
                } else {
                    // The chat page.
                    Box(Modifier.fillMaxSize().background(Theme.canvas)) {
                        Column(Modifier.fillMaxSize()) {
                            InboxOlfatoChatBar(
                                title = if (model.chat != null || model.localTitle != null) model.title else "Olfato AI",
                                petLine = if (model.chat?.way == "pet") model.chat?.about else null,
                                subtitle = L("About your business"),
                                onChats = { listOpen.value = true },
                                menu = model.chat?.let { chat -> Pair({ renaming = chat }, { deleting = chat }) },
                                onNew = if (begun) ({ startNew() }) else null,
                            )
                            if (model.gone) {
                                PageScroll {
                                    EmptyState(
                                        systemImage = "message-square-warning",
                                        title = L("That chat is not here any more."),
                                        message = L("Only you can read your chats, for 90 days."),
                                        panel = true,
                                    )
                                }
                            } else {
                                InboxOlfatoThread(model = model, viewer = viewer, canEditWorkspace = canEditWorkspace, modifier = Modifier.weight(1f))
                            }
                        }
                        InboxOlfatoDrawer(isOpen = listOpen) {
                            InboxOlfatoChatListView(
                                list = list,
                                activeId = model.chatId,
                                searchable = true,
                                onOpen = { id ->
                                    open(id)
                                    listOpen.value = false
                                },
                                onRename = { renaming = it },
                                onDelete = { deleting = it },
                            ) {
                                if (gate == InboxOlfatoGate.ready) {
                                    PMButton(L("New chat"), systemImage = "message-square-plus", fullWidth = true) {
                                        startNew()
                                        listOpen.value = false
                                    }
                                }
                            }
                        }
                    }
                    LaunchedEffect(model) {
                        val current = model
                        current.onGateRefusal = { scope.launch { viewer.load() } }
                        current.load()
                        loadedOnce = true
                    }
                }
            }
        }
    }

    ReloadsOnScope {
        viewer.load()
        if (viewer.gate.readable) list.load()
    }
    // Back on the chat: read it again, unless a reply is being followed here.
    InboxOnReturn {
        if (loadedOnce && !model.live) scope.launch { model.loadMessages() }
    }
    // The first question gave the chat its id: it joins the list.
    val lastChatId = remember { arrayOf(model.chatId) }
    LaunchedEffect(Unit) {
        snapshotFlow { model.chatId }.collect { new ->
            val old = lastChatId[0]
            lastChatId[0] = new
            if (old == null && new != null) scope.launch { list.load() }
        }
    }
    // Leaving only stops watching: the reply is finished and kept on the server.
    DisposableEffect(Unit) { onDispose { model.leave() } }

    renaming?.let { chat ->
        AppSheet(onDismissRequest = { renaming = null }) {
            InboxRenameChatSheet(title = chat.displayTitle) { title -> rename(chat, title) }
        }
    }
    deleting?.let { chat ->
        AppSheet(onDismissRequest = { deleting = null }) {
            InboxDeleteChatSheet(title = chat.displayTitle) { delete(chat) }
        }
    }
}

// MARK: - The chat bar

/**
 * The bar over a chat (web olfato-business-page header): 56 tall, white, a hairline under it; "Tus chats"
 * (a 40 ghost circle), the vet-coat corgi at 28, the chat's name in Fredoka 18 and what it is about in
 * 12, the chat's ⋯, and "Nuevo chat" (a 40 button; its icon alone on a phone).
 * `menu`: the ⋯'s rename and delete (Swift `(rename:delete:)`).
 */
@Composable
fun InboxOlfatoChatBar(
    title: String,
    /** "About Luna" for a chat about a pet (with the paw); else `subtitle`. */
    petLine: String? = null,
    subtitle: String,
    onChats: (() -> Unit)? = null,
    menu: Pair<() -> Unit, () -> Unit>? = null,
    onNew: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val regular = LocalSizeClass.current == SizeClass.regular
    Box(modifier.fillMaxWidth().background(Theme.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = if (regular) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onChats != null) {
                Box(
                    Modifier.size(40.dp).plainButton(label = L("Your chats"), onClick = onChats),
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph("panel-left", 16.dp, tint = Theme.inkStrong)
                }
            }
            InboxOlfatoArtwork("OlfatoFace", Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Theme.heading(18), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (petLine != null) {
                    CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("paw-print", 12.dp)
                            Text(petLine, style = Theme.text(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Text(subtitle, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // SwiftUI `Spacer(minLength: 4)`.
            Spacer(Modifier.width(4.dp))
            if (menu != null) {
                AppMenu(label = { IconCircleLabel(size = 40.dp, bordered = false) }, accessibilityLabel = LF("More for “%@”", title)) {
                    item(L("Rename"), icon = "pencil") { menu.first() }
                    item(L("Delete"), icon = "trash-2", destructive = true) { menu.second() }
                }
            }
            if (onNew != null) {
                if (regular) {
                    PMButton(L("New chat"), systemImage = "message-square-plus", size = ButtonSize.compact, action = onNew)
                } else {
                    val label = L("New chat")
                    PMButtonStyle(
                        ButtonVariant.primary,
                        ButtonSize.compact,
                        modifier = Modifier.semantics { contentDescription = label },
                        onClick = onNew,
                    ) {
                        Glyph("message-square-plus", 16.dp)
                    }
                }
            }
        }
        RowDivider(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

// MARK: - Tus chats

/** The chats' panel from the left (web: the sidebar's sheet, min(22 rem, 88% of the width)) over a veil. */
@Composable
private fun InboxOlfatoDrawer(isOpen: MutableState<Boolean>, content: @Composable () -> Unit) {
    val motion = tween<Float>(220, easing = EaseOut)
    fun close() {
        isOpen.value = false
    }
    // Android's back closes the panel first.
    BackHandler(enabled = isOpen.value) { close() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth: Dp = min(352.dp, maxWidth * 0.88f)
        AnimatedVisibility(isOpen.value, enter = fadeIn(motion), exit = fadeOut(motion)) {
            val closeWord = L("Close")
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Theme.inkStrong.copy(alpha = 0.28f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
                    .semantics {
                        contentDescription = closeWord
                        role = Role.Button
                    }
            )
        }
        AnimatedVisibility(
            isOpen.value,
            enter = slideInHorizontally(tween(220, easing = EaseOut)) { -it },
            exit = slideOutHorizontally(tween(220, easing = EaseOut)) { -it },
        ) {
            Box(
                Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .softShadow(RectangleShape, Theme.navy.copy(alpha = 0.14f), radius = 24.dp, x = 12.dp)
                    .background(Theme.surface)
                    // The panel takes the touches meant for the veil under it.
                    .pointerInput(Unit) {}
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(L("Your chats"), style = Theme.heading(18), color = Theme.inkStrong)
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.size(40.dp).plainButton(label = L("Close")) { close() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Glyph("x", 16.dp, tint = Theme.inkSoft)
                        }
                    }
                    RowDivider()
                    Box(Modifier.weight(1f)) { content() }
                }
                Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(Theme.borderSoft))
            }
        }
    }
}

/** Swift `folding(options: [.diacriticInsensitive, .caseInsensitive])` and trimmed. */
private fun inboxFolded(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

/**
 * The person's chats, newest first under "Hoy / Ayer / Esta semana / Antes", 20 at a time with the
 * next page as the end nears (web `OlfatoChatList`): a search by name, each chat's name and when,
 * its pet when it is about one, and its ⋯ (rename, delete). `top`: what stands over the search.
 */
@Composable
fun InboxOlfatoChatListView(
    list: InboxOlfatoChatListModel,
    activeId: String?,
    showPets: Boolean = true,
    searchable: Boolean = false,
    onOpen: (String) -> Unit,
    onRename: (InboxOlfatoChat) -> Unit,
    onDelete: (InboxOlfatoChat) -> Unit,
    modifier: Modifier = Modifier,
    top: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val query = remember { mutableStateOf("") }
    val sorted = list.chats.sortedByDescending { it.sortDate }
    val needle = inboxFolded(query.value)
    val shown = if (needle.isEmpty()) sorted else sorted.filter { inboxFolded(it.displayTitle).contains(needle) }
    var refreshing by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try { list.load() } finally { refreshing = false }
            }
        },
        modifier = modifier.fillMaxSize(),
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = Theme.surface,
                color = Theme.inkSoft,
            )
        },
    ) {
        LazyColumn(Modifier.fillMaxSize().scrollDismissesKeyboard(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "top") {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    top()
                    if (searchable) SearchField(prompt = L("Search your chats"), text = query, height = 40.dp)
                }
            }
            when {
                !list.ready -> item(key = "loading") { LoadingState(text = L("Loading your chats")) }
                list.failed && list.chats.isEmpty() -> item(key = "failed") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(L("Your chats could not be loaded."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                        PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { list.load() } }
                    }
                }
                list.chats.isEmpty() -> item(key = "empty") {
                    Text(
                        L("Your chats will be listed here. Only you can read them, for 90 days."),
                        Modifier.padding(horizontal = 16.dp),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                }
                shown.isEmpty() -> item(key = "none") {
                    Text(
                        LF("No chat matches “%@”.", query.value.trim()),
                        Modifier.padding(horizontal = 16.dp),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                }
                else -> InboxChatDay.groups(shown).forEach { (day, chats) ->
                    item(key = "day-${day.name}") {
                        Text(
                            day.label.uppercase(),
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                            style = brandText(12, FontWeight.Bold).tracking(0.3),
                            color = Theme.inkSoft,
                        )
                    }
                    itemsIndexed(chats, key = { _, chat -> chat.id }) { index, chat ->
                        InboxChatRow(
                            chat = chat,
                            showsAbout = showPets,
                            selected = chat.id == activeId,
                            onOpen = { onOpen(chat.id) },
                            onRename = { onRename(chat) },
                            onDelete = { onDelete(chat) },
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = if (index > 0) 2.dp else 0.dp, bottom = if (index == chats.lastIndex) 8.dp else 0.dp),
                        )
                        LaunchedEffect(Unit) {
                            if (chat.id == shown.lastOrNull()?.id) scope.launch { list.loadMore() }
                        }
                    }
                }
            }
            if (list.hasMore || (list.failed && list.chats.isNotEmpty())) {
                item(key = "more") {
                    PMButton(
                        if (list.loadingMore) L("Loading…") else (if (list.failed) L("Try again") else L("Show more")),
                        systemImage = "chevron-down",
                        variant = ButtonVariant.outline,
                        size = ButtonSize.compact,
                        fullWidth = true,
                        isWorking = list.loadingMore,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        scope.launch { list.loadMore() }
                    }
                }
            }
        }
    }
}

/** Chats under their day in the business's calendar: today, yesterday, the rest of this week, before. */
enum class InboxChatDay {
    today, yesterday, week, earlier;

    val label: String
        get() = when (this) {
            today -> L("Today")
            yesterday -> L("Yesterday")
            week -> L("This week")
            earlier -> L("Earlier")
        }

    companion object {
        /** Swift `[(day:chats:)]`: each day with its chats, in the days' order. */
        fun groups(chats: List<InboxOlfatoChat>): List<Pair<InboxChatDay, List<InboxOlfatoChat>>> {
            val zone = Workspace.shared.timeZone
            val todayDate = LocalDate.now(zone)
            // Monday 0 … Sunday 6.
            val sinceMonday = todayDate.dayOfWeek.value - 1
            val groups = mutableMapOf<InboxChatDay, MutableList<InboxOlfatoChat>>()
            for (chat in chats) {
                val day = chat.sortDate.atZone(zone).toLocalDate()
                val ago = ChronoUnit.DAYS.between(day, todayDate)
                val group = if (ago <= 0) today else (if (ago == 1L) yesterday else (if (ago <= sinceMonday) week else earlier))
                groups.getOrPut(group) { mutableListOf() }.add(chat)
            }
            return entries.mapNotNull { day -> groups[day]?.let { day to it } }
        }
    }
}

/**
 * One chat in the list (web `OlfatoChatList` row): its name in 14, under it the pet (when it is about
 * one) and when, or "Respondiendo…"; the open one on surface-muted; its ⋯ beside it.
 */
@Composable
fun InboxChatRow(
    chat: InboxOlfatoChat,
    /** The pet under the title; a pet's own list leaves it out. */
    showsAbout: Boolean = true,
    selected: Boolean = false,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = chat.sortDate
    val zone = Workspace.shared.timeZone
    val whenText = if (date.atZone(zone).toLocalDate() == LocalDate.now(zone)) DateText.time(date) else DateText.date(date)
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(if (selected) Theme.surfaceMuted else Color.Transparent, shape)
                .plainButton(onClick = onOpen)
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Text(
                chat.displayTitle,
                style = Theme.text(14, if (selected) FontWeight.Bold else FontWeight.Normal),
                color = Theme.inkStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CompositionLocalProvider(LocalContentColor provides Theme.inkSoft, LocalTextStyle provides Theme.text(12)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showsAbout && chat.way == "pet") {
                        Glyph("paw-print", 12.dp)
                        Text(chat.petName.nonEmpty ?: L("a pet"), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("·")
                    }
                    Text(if (chat.replying == true) L("Answering…") else whenText, maxLines = 1)
                }
            }
        }
        AppMenu(label = { IconCircleLabel(size = 40.dp, bordered = false) }, accessibilityLabel = LF("More for “%@”", chat.displayTitle)) {
            item(L("Rename"), icon = "pencil") { onRename() }
            item(L("Delete"), icon = "trash-2", destructive = true) { onDelete() }
        }
    }
}

// MARK: - Rename and delete

@Composable
fun InboxRenameChatSheet(title: String, modifier: Modifier = Modifier, save: suspend (String) -> Unit) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    // iOS `.onAppear { value = title }`.
    val value = remember { mutableStateOf(title) }
    var saving by remember { mutableStateOf(false) }

    ProviderModal(
        title = L("Rename chat"),
        subtitle = L("Only you see this name."),
        systemImage = "pencil",
        accent = ModalAccent.aqua,
        isDirty = value.value != title,
        form = form,
        primaryTitle = L("Save"),
        isWorking = saving,
        onPrimary = onPrimary@{
            val next = value.value.trim()
            val length = next.codePointCount(0, next.length)
            if (!form.check(listOf("title" to (if (next.isEmpty()) L("Write a name for the chat.") else (if (length > 80) LF("Keep it under %d characters.", 80) else null))))) return@onPrimary
            saving = true
            scope.launch {
                try {
                    save(next)
                    dismiss()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    form.apply(failure, mapOf("title" to "title"))
                } finally {
                    saving = false
                }
            }
        },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium)),
    ) {
        PMTextField(L("Name of the chat"), text = value, key = "title", form = form)
    }
}

@Composable
fun InboxDeleteChatSheet(title: String, modifier: Modifier = Modifier, delete: suspend () -> Unit) {
    ConfirmSheet(
        title = L("Delete this chat?"),
        message = LF("“%@” is deleted for good, its questions and answers with it. The log of who asked stays, without the text.", title),
        confirmTitle = L("Delete"),
        destructive = true,
        modifier = modifier,
    ) { _ ->
        try {
            delete()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Toast.error(L("The chat could not be deleted. Try again."))
            throw failure
        }
    }
}

// MARK: - Gates

/** A gate as the whole page: nothing to read yet, and nothing can be asked. */
@Composable
fun InboxOlfatoGatePage(
    gate: InboxOlfatoGate,
    canEditWorkspace: Boolean,
    modifier: Modifier = Modifier,
    retry: (suspend () -> Unit)? = null,
) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    PageScroll(modifier = modifier) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 576.dp).fillMaxWidth().padding(vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InboxOlfatoArtwork("OlfatoAI", Modifier.size(96.dp))
                Text(
                    if (gate == InboxOlfatoGate.privateSession) L("Chats are private.") else "Olfato AI",
                    style = Theme.heading(24),
                    color = Theme.inkStrong,
                    textAlign = TextAlign.Center,
                )
                Text(gate.sentence, style = Theme.text(14), color = Theme.inkSoft, textAlign = TextAlign.Center)
                if (gate == InboxOlfatoGate.off && canEditWorkspace) {
                    PMButtonStyle(
                        ButtonVariant.primary,
                        modifier = Modifier.padding(top = 8.dp),
                        onClick = { nav.push(ProviderRoute.section(ProviderSection.settings)) },
                    ) {
                        PMButtonContent(L("Turn it on in Settings"), icon = "settings")
                    }
                }
                if (gate == InboxOlfatoGate.error && retry != null) {
                    PMButton(L("Try again"), variant = ButtonVariant.outline, modifier = Modifier.padding(top = 8.dp)) {
                        scope.launch { retry() }
                    }
                }
            }
        }
    }
}

/** A ring `width` wide just outside the box (iOS `.inset(by: -3).strokeBorder(color, lineWidth: 3)`). */
private fun Modifier.inboxOuterRing(color: Color, radius: Dp, width: Dp = 3.dp): Modifier = drawBehind {
    val w = width.toPx()
    val r = radius.toPx() + w / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(-w / 2, -w / 2),
        size = Size(size.width + w, size.height + w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(w),
    )
}

/**
 * The notice as the whole page. Someone who edits the workspace reads it and confirms it here; anyone else
 * is told the business has to.
 */
@Composable
fun InboxOlfatoNoticeGate(canConfirm: Boolean, modifier: Modifier = Modifier, confirmed: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    suspend fun confirm() {
        if (!checked) {
            val sentence = L("Tick the confirmation to use the chat.")
            error = sentence
            Toast.error(sentence)
            return
        }
        saving = true
        try {
            // The notice alone: the switch stays as the business left it.
            API.send("PUT", "/provider-patients/ai-summary-settings", mapOf("acknowledged" to true))
            Toast.show(L("Confirmed. Your team can use Olfato AI."))
            confirmed()
            announceDataChanged()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Toast.error(failure, L("It could not be saved. Try again."))
        } finally {
            saving = false
        }
    }

    PageScroll(maxWidth = 1000.dp, modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (canConfirm) L("Before your team uses the chat") else L("Your business has to accept the Olfato AI notice."),
                    style = Theme.heading(24),
                    color = Theme.inkStrong,
                )
                Text(
                    if (canConfirm) L("The Olfato AI notice changed: it now covers the chat too. Read it and confirm it to use Olfato AI.")
                    else L("Someone who edits the workspace, an owner or an admin, confirms it in Settings › Olfato AI. Until then the chat waits."),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
            InboxOlfatoArtwork("OlfatoAI", Modifier.size(80.dp))
        }
        if (canConfirm) {
            OlfatoNotice(minimumWidth = 300.dp)
            val shape = RoundedCornerShape(Theme.fieldRadius)
            val problem = error
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (problem != null) Modifier.inboxOuterRing(Theme.coral.copy(alpha = 0.2f), Theme.fieldRadius) else Modifier)
                    .background(Theme.surface, shape)
                    .border(1.dp, if (problem != null) Theme.coral else Theme.borderSoft, shape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PMSwitchStyle(isOn = stateOf({ checked }, { value -> checked = value; if (value) error = null })) {
                    Text(
                        L("I’ve read this and our clients are informed as our policies require"),
                        style = Theme.text(15, FontWeight.SemiBold),
                        color = Theme.inkStrong,
                    )
                }
                if (problem != null) {
                    Text(problem, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                PMButton(L("Confirm and open the chat"), isWorking = saving) { scope.launch { confirm() } }
            }
        }
    }
}
