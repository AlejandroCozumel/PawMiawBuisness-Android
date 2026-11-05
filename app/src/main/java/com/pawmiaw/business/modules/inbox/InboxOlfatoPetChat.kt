package com.pawmiaw.business.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.records.*
import kotlinx.coroutines.launch

//
//  InboxOlfatoPetChat.kt (iOS InboxOlfatoPetChat.swift)
//
//  The pet way into Olfato AI (web olfato-business/olfato-pet-drawer.tsx):
//  "Preguntar a Olfato" on a pet's chart opens the same chat as the Olfato AI
//  page, about this pet. The notice and the other gates stand first; then a
//  chat not begun ("¿Qué quieres saber de Luna?", the pet's questions, the
//  panel folded into one line), made on the server about the pet (`way: pet`,
//  the provider client pet id) with its first question. This person's earlier
//  chats about the pet are a tap away ("Chats anteriores"), and "Nuevo chat"
//  starts over. Chats are private to whoever asked, for 90 days.
//

/** A new Olfato AI chat about one pet, or one of the earlier ones about it. `petId` is the provider client pet id. */
@Composable
fun OlfatoPetChatScreen(petId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val viewer = remember { InboxOlfatoViewer() }
    val list = remember { InboxOlfatoChatListModel(way = "pet", petId = petId) }
    /** The open chat; a new one (null id) until a question makes it. */
    var model by remember { mutableStateOf(InboxOlfatoChatModel(chatId = null, petId = petId, petName = null)) }
    var petName by remember { mutableStateOf<String?>(null) }
    var lookedUp by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<InboxOlfatoChat?>(null) }
    var deleting by remember { mutableStateOf<InboxOlfatoChat?>(null) }

    val gate = viewer.gate
    val canEditWorkspace = workspace.can(Permission.workspaceEdit)
    val pet = InboxOlfatoPetRef(id = petId, name = petName ?: L("this pet"))
    val askTitle = petName?.let { LF("Ask Olfato about %@", it) } ?: L("Ask Olfato")

    // MARK: The pet's name

    /** From an earlier chat about it, else from its records, visits or client (there is no route for one pet). */
    suspend fun findName() {
        if (petName == null) {
            list.chats.firstOrNull { it.petId == petId }?.petName.nonEmpty?.let { petName = it }
        }
        if (petName == null && !lookedUp) {
            petName = RecordsPetLookup.find(petId).petName
        }
        lookedUp = true
        model.petName = petName
    }

    // MARK: Moving between chats

    fun open(id: String) {
        if (id == model.chatId) return
        // Only stops watching: a reply still being written is finished and kept on the server.
        model.leave()
        model = InboxOlfatoChatModel(chatId = id, petId = petId, petName = petName)
    }

    fun startNew() {
        if (!(model.chat != null || model.messages.isNotEmpty() || model.gone)) return
        model.leave()
        model = InboxOlfatoChatModel(chatId = null, petId = petId, petName = petName)
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
            model = InboxOlfatoChatModel(chatId = null, petId = petId, petName = petName)
        } else {
            try {
                API.send("DELETE", "/provider-olfato/chats/${chat.id}")
            } catch (error: APIError) {
                // Gone already is what was asked for.
                if (!(error.code == "olfato_chat_not_found" || error.status == 404)) throw error
            }
            list.remove(chat.id)
            if (chat.id == model.chatId) model = InboxOlfatoChatModel(chatId = null, petId = petId, petName = petName)
        }
    }

    /** iOS `.navigationDestination(isPresented: $showingList)`: the earlier chats slide in as a page. */
    fun showList() {
        nav.push(
            ProviderRoute.page("olfato-pet-chats:$petId") {
                InboxOlfatoPetChatList(
                    list = list,
                    pet = InboxOlfatoPetRef(id = petId, name = petName ?: L("this pet")),
                    activeId = model.chatId,
                    canStart = viewer.gate == InboxOlfatoGate.ready,
                    onOpen = { open(it) },
                    onNew = { startNew() },
                    rename = { chat, title -> rename(chat, title) },
                    delete = { chat -> delete(chat) },
                )
            }
        )
    }

    ReloadsOnScope {
        viewer.load()
        if (viewer.gate.readable) list.load()
        findName()
    }
    // Leaving only stops watching: the reply is finished and kept on the server.
    DisposableEffect(Unit) { onDispose { model.leave() } }

    when (gate) {
        InboxOlfatoGate.loading -> providerPage(askTitle, modifier = modifier) {
            PageScroll { LoadingState(text = L("Loading Olfato AI")) }
        }
        InboxOlfatoGate.notice -> providerPage(askTitle, modifier = modifier) {
            InboxOlfatoNoticeGate(canConfirm = canEditWorkspace) {
                viewer.load()
                list.load()
            }
        }
        InboxOlfatoGate.privateSession, InboxOlfatoGate.error -> providerPage(askTitle, modifier = modifier) {
            InboxOlfatoGatePage(gate = gate, canEditWorkspace = canEditWorkspace) { viewer.load() }
        }
        else -> {
            if (gate != InboxOlfatoGate.ready && list.ready && list.chats.isEmpty()) {
                // Nothing to read and nothing can be asked: why, as the whole page.
                providerPage(askTitle, modifier = modifier) {
                    InboxOlfatoGatePage(gate = gate, canEditWorkspace = canEditWorkspace, retry = null)
                }
            } else if (!lookedUp) {
                providerPage(askTitle, modifier = modifier) {
                    PageScroll { LoadingState(text = L("Loading the chat")) }
                }
            } else {
                InboxOlfatoPetThreadView(
                    model = model,
                    viewer = viewer,
                    pet = pet,
                    canEditWorkspace = canEditWorkspace,
                    earlier = list.chats.size,
                    moreEarlier = list.hasMore,
                    onList = { showList() },
                    onNew = { startNew() },
                    onRename = { renaming = it },
                    onDelete = { deleting = it },
                    modifier = modifier,
                )
            }
        }
    }

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

// MARK: - The chat

/** The open chat about the pet: its name and menu above, "Chats anteriores" and "Nuevo chat", then the thread. */
@Composable
private fun InboxOlfatoPetThreadView(
    model: InboxOlfatoChatModel,
    viewer: InboxOlfatoViewer,
    pet: InboxOlfatoPetRef,
    canEditWorkspace: Boolean,
    earlier: Int,
    moreEarlier: Boolean,
    onList: () -> Unit,
    onNew: () -> Unit,
    onRename: (InboxOlfatoChat) -> Unit,
    onDelete: (InboxOlfatoChat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val title = model.chat?.displayTitle ?: model.localTitle ?: LF("Ask Olfato about %@", pet.name)
    val begun = model.chat != null || model.messages.isNotEmpty()

    providerPage(title, modifier = modifier) {
        Column(Modifier.fillMaxSize().background(Theme.canvas)) {
            // The drawer's header (web olfato-pet-drawer): the corgi, the chat's name, "Only you can read…", its ⋯;
            // under it "Chats anteriores (3)" and "Nuevo chat" while chats can be read.
            InboxOlfatoChatBar(
                title = title,
                subtitle = L("Only you can read your chats, for 90 days."),
                menu = if (model.gone) null else model.chat?.let { chat -> Pair({ onRename(chat) }, { onDelete(chat) }) },
            )
            if (viewer.gate.readable && (earlier > 0 || begun || model.gone)) {
                Box(Modifier.fillMaxWidth().background(Theme.surface)) {
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (earlier > 0) {
                            PMButton(
                                LF("Earlier chats (%@)", if (moreEarlier) "$earlier+" else "$earlier"),
                                systemImage = "history",
                                variant = ButtonVariant.outline,
                                size = ButtonSize.header,
                                action = onList,
                            )
                        }
                        if (begun || model.gone) {
                            // A reply still being written is finished and kept on the server.
                            PMButton(L("New chat"), systemImage = "message-square-plus", variant = ButtonVariant.outline, size = ButtonSize.header, action = onNew)
                        }
                    }
                    RowDivider(modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
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
                InboxOlfatoThread(model = model, viewer = viewer, canEditWorkspace = canEditWorkspace, pet = pet, compact = true, modifier = Modifier.weight(1f))
            }
        }
    }
    LaunchedEffect(model) {
        val current = model
        current.onGateRefusal = { scope.launch { viewer.load() } }
        current.load()
    }
}

// MARK: - The earlier chats about the pet

@Composable
private fun InboxOlfatoPetChatList(
    list: InboxOlfatoChatListModel,
    pet: InboxOlfatoPetRef,
    activeId: String?,
    canStart: Boolean,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    rename: suspend (InboxOlfatoChat, String) -> Unit,
    delete: suspend (InboxOlfatoChat) -> Unit,
) {
    val dismiss = LocalDismiss.current
    var renaming by remember { mutableStateOf<InboxOlfatoChat?>(null) }
    var deleting by remember { mutableStateOf<InboxOlfatoChat?>(null) }

    providerPage(LF("Earlier chats about %@", pet.name)) {
        Box(Modifier.fillMaxSize().background(Theme.surface)) {
            InboxOlfatoChatListView(
                list = list,
                activeId = activeId,
                showPets = false,
                onOpen = { id ->
                    onOpen(id)
                    dismiss()
                },
                onRename = { renaming = it },
                onDelete = { deleting = it },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        InboxOlfatoArtwork("OlfatoFace", Modifier.size(32.dp))
                        Text(L("Only you can read your chats, for 90 days."), style = Theme.text(12), color = Theme.inkSoft)
                    }
                    if (canStart) {
                        PMButton(LF("New chat about %@", pet.name), systemImage = "message-square-plus", fullWidth = true) {
                            onNew()
                            dismiss()
                        }
                    }
                }
            }
        }
    }

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
