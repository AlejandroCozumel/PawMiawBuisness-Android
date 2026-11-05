package com.pawmiaw.business.inbox

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

//
//  InboxOlfatoChatModel.kt (iOS InboxOlfatoChatModel.swift)
//
//  One Olfato AI chat as the server keeps it (web
//  olfato-business/use-olfato-business-chat.ts): reading it, asking, the
//  tools a reply runs, stopping, answering again, rating, renaming and
//  deleting. Nothing is kept on the device: what is on screen before the
//  server answered (a question just sent, a reply on its way) is `local`
//  and gives way to the server's rows as they arrive. Leaving the screen
//  only stops watching: the reply is finished and kept on the server.
//

data class InboxFormRefusal(
    val sentence: String,
    val question: String,
)

@Serializable
private data class InboxStopAnswer(
    val stopped: Boolean? = null,
    val reply: InboxOlfatoMessageRow? = null,
)

@Stable
class InboxOlfatoChatModel(chatId: String?, petId: String?, petName: String?) {
    constructor(chatId: String?) : this(chatId, null, null)

    var chat: InboxOlfatoChat? by mutableStateOf(null)
        private set
    var messages: List<InboxOlfatoMessage> by mutableStateOf(emptyList())
        private set
    var loaded: Boolean by mutableStateOf(false)
        private set
    var hasOlder: Boolean by mutableStateOf(false)
        private set
    var loadingOlder: Boolean by mutableStateOf(false)
        private set
    /** A reply this device is following. */
    var live: Boolean by mutableStateOf(false)
        private set
    /** The chat asked for is not there (deleted, or someone else's). */
    var gone: Boolean by mutableStateOf(false)
        private set
    /** The last question refused for what was typed: the box takes it back and says why. */
    var formRefusal: InboxFormRefusal? by mutableStateOf(null)
    /** A title for a chat not made on the server yet. */
    var localTitle: String? by mutableStateOf(null)
        private set

    /** A refusal about who may ask (the notice, the switch, the access): the panel is read again. */
    var onGateRefusal: (() -> Unit)? = null

    private val requestedId: String? = chatId
    /** A chat about this pet (the provider client pet id), from its chart: a new chat is made about it. */
    val petId: String? = petId.nonEmpty
    /** The pet's name, for a chat not made on the server yet. */
    var petName: String? = petName
    private var newId: String? = null
    private var task: Job? = null
    private var runToken = UUID.randomUUID()
    private var started = false
    private var stopWanted = false
    private var pollTask: Job? = null
    private var olderLoaded = false

    /** Swift `Task { }` on the main actor: the model's own work, not tied to a screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val chatId: String? get() = chat?.id ?: requestedId
    val language: String get() = LocalizationManager.currentLanguage.rawValue
    val arrivingElsewhere: Boolean get() = !live && messages.any { !it.local && it.arriving }
    /** A reply is on its way in this chat: nothing else can be asked in it. */
    val busy: Boolean get() = live || arrivingElsewhere
    val fresh: Boolean get() = loaded && messages.isEmpty()
    val title: String get() = chat?.displayTitle ?: localTitle ?: "Olfato AI"

    private fun path(id: String, rest: String = ""): String =
        "/provider-olfato/chats/${android.net.Uri.encode(id, "/:@!$&'()*+,;=-._~")}$rest"

    // MARK: Reading

    suspend fun load() {
        val id = requestedId
        if (id == null || !(chat == null || !live)) {
            loaded = true
            return
        }
        try {
            val envelope = API.get<InboxOlfatoChatEnvelope>(path(id))
            chat = envelope.chat
            gone = false
        } catch (cancelled: CancellationException) {
            return
        } catch (failure: Throwable) {
            val refusal = InboxOlfatoRefusal.from(failure)
            if (refusal.code == "olfato_chat_not_found" || refusal.status == 404) gone = true
            note(refusal)
        }
        loadMessages()
    }

    suspend fun loadMessages() {
        val id = chatId
        if (id == null || gone) {
            loaded = true
            return
        }
        try {
            val page = API.get<InboxOlfatoMessagesPage>(path(id, "/messages"))
            val rows = (page.messages ?: emptyList()).map { InboxOlfatoMessage(it) }
            messages = merge(rows)
            if (!olderLoaded) hasOlder = page.hasOlder ?: false
        } catch (cancelled: CancellationException) {
            return
        } catch (failure: Throwable) {
            note(InboxOlfatoRefusal.from(failure))
        }
        loaded = true
        updatePolling()
    }

    /** A page read from the server laid over what is on screen. */
    private fun merge(page: List<InboxOlfatoMessage>): List<InboxOlfatoMessage> {
        val lowest = page.mapNotNull { it.seq }.minOrNull() ?: Int.MAX_VALUE
        val older = messages.filter { (it.seq ?: Int.MAX_VALUE) < lowest && !it.local }
        val known = page.mapNotNull { it.clientMessageId }.toSet()
        val local = mutableListOf<InboxOlfatoMessage>()
        var dropNextReply = false
        for (message in messages) {
            if (!message.local) continue
            if (message.isUser) {
                dropNextReply = message.clientMessageId?.let { it in known } ?: false
                if (!dropNextReply) local.add(message)
            } else if (!dropNextReply) {
                local.add(message)
            }
        }
        return older + page + local
    }

    suspend fun loadOlder() {
        val id = chatId ?: return
        if (loadingOlder) return
        val lowest = messages.mapNotNull { it.seq }.minOrNull() ?: return
        loadingOlder = true
        try {
            val page = API.get<InboxOlfatoMessagesPage>(path(id, "/messages"), mapOf("before" to "$lowest"))
            messages = (page.messages ?: emptyList()).map { InboxOlfatoMessage(it) } + messages
            hasOlder = page.hasOlder ?: false
            olderLoaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            note(InboxOlfatoRefusal.from(failure))
        } finally {
            loadingOlder = false
        }
    }

    /** A reply being written elsewhere (another device, or here before the screen was opened) is read again until it ends. */
    private fun updatePolling() {
        if (arrivingElsewhere) {
            if (pollTask != null) return
            pollTask = scope.launch {
                while (isActive) {
                    delay(1500)
                    loadMessages()
                    if (!arrivingElsewhere) break
                }
                pollTask = null
            }
        } else {
            pollTask?.cancel()
            pollTask = null
        }
    }

    fun leave() {
        // Leaving only stops watching: the reply is finished and kept on the server.
        task?.cancel()
        pollTask?.cancel()
        pollTask = null
    }

    private fun note(refusal: InboxOlfatoRefusal) {
        if (refusal.code == "olfato_chat_not_found") gone = true
        if (refusal.isGate) onGateRefusal?.invoke()
    }

    // MARK: Writing in place

    /** Changes one message: `change` gets a copy and gives back what takes its place. */
    private fun patch(id: String, change: (InboxOlfatoMessage) -> InboxOlfatoMessage) {
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return
        messages = messages.toMutableList().also { it[index] = change(it[index].copy()) }
    }

    private fun markStopped(replyId: String) {
        patch(replyId) { message ->
            message.apply {
                status = InboxOlfatoMessage.Status.stopped
                errorCode = null
                tools = tools.filter { it.status != "running" }
            }
        }
    }

    private fun markFailed(replyId: String, refusal: InboxOlfatoRefusal) {
        patch(replyId) { message ->
            message.apply {
                status = InboxOlfatoMessage.Status.error
                errorCode = refusal.code
                errorData = refusal.data
                tools = tools.filter { it.status != "running" }
            }
        }
    }

    // MARK: Asking

    private fun begin(work: suspend (UUID) -> Unit) {
        task?.cancel()
        val token = UUID.randomUUID()
        runToken = token
        live = true
        started = false
        stopWanted = false
        updatePolling()
        task = scope.launch { work(token) }
    }

    private fun end(token: UUID) {
        if (runToken != token) return
        live = false
        task = null
        started = false
        stopWanted = false
        updatePolling()
    }

    /** Sends a question; false when nothing was sent (empty, or a reply is on its way). */
    fun ask(text: String): Boolean {
        val content = text.trim()
        if (content.isEmpty() || busy || gone) return false
        val clientMessageId = UUID.randomUUID().toString().lowercase()
        val question = InboxOlfatoMessage(id = clientMessageId, isUser = true, content = content, local = true, clientMessageId = clientMessageId)
        val replyId = "reply-$clientMessageId"
        val reply = InboxOlfatoMessage(id = replyId, isUser = false, content = "", status = InboxOlfatoMessage.Status.thinking, local = true)
        val exists = chat != null
        val current = chatId
        val id: String
        if (current != null && (exists || requestedId != null)) {
            id = current
        } else {
            id = newId ?: UUID.randomUUID().toString().lowercase()
            newId = id
            val line = content.replace(Regex("\\s+"), " ")
            localTitle = if (line.length > 44) line.take(44) + "…" else line
        }
        messages = messages + listOf(question, reply)
        loaded = true
        begin { token ->
            send(chatId = id, exists = exists, question = question, replyId = replyId, token = token)
        }
        return true
    }

    private suspend fun send(chatId: String, exists: Boolean, question: InboxOlfatoMessage, replyId: String, token: UUID) {
        val id = chatId
        if (!exists) {
            try {
                // About the pet when it came from a chart; the pet is left out of a business chat, never null.
                val body = mutableMapOf<String, Any?>("id" to id, "way" to (if (petId == null) "business" else "pet"))
                if (petId != null) body["providerClientPetId"] = petId
                val envelope = API.post<InboxOlfatoChatEnvelope>("/provider-olfato/chats", body)
                chat = envelope.chat
                announceDataChanged()
            } catch (cancelled: CancellationException) {
                end(token)
                return
            } catch (failure: Throwable) {
                val refusal = InboxOlfatoRefusal.from(failure)
                markFailed(replyId, refusal)
                note(refusal)
                end(token)
                return
            }
            // Stopped (or let go) while the chat was being made: nothing was asked.
            if (stopWanted || !currentCoroutineContext().isActive) {
                markStopped(replyId)
                end(token)
                return
            }
        }
        follow(
            path = path(id, "/messages"),
            body = mapOf("clientMessageId" to (question.clientMessageId ?: question.id), "content" to question.content, "language" to language),
            question = question,
            shownReplyId = replyId,
            resend = true,
            token = token,
        )
    }

    private suspend fun follow(path: String, body: Map<String, Any?>, question: InboxOlfatoMessage?, shownReplyId: String, resend: Boolean, token: UUID) {
        var replyId = shownReplyId
        var startedHere = false
        var finished = false
        var failure: InboxOlfatoRefusal? = null

        for (attempt in 0 until (if (resend) 2 else 1)) {
            failure = null
            try {
                InboxOlfatoStream.run(path, body) { event ->
                    when (event) {
                        is InboxOlfatoEvent.start -> {
                            startedHere = true
                            started = true
                            event.chat?.let { chat = it }
                            val replyRow = event.reply
                            if (replyRow != null) {
                                val serverQuestion = event.question?.let { InboxOlfatoMessage(it) }
                                val serverReply = InboxOlfatoMessage(replyRow).apply {
                                    status = InboxOlfatoMessage.Status.thinking
                                    tools = emptyList()
                                    links = emptyList()
                                }
                                val fromId = replyId
                                replyId = serverReply.id
                                // The server's rows take the place of what stood for them; a reply answered again leaves.
                                messages = messages.filterNot { message ->
                                    message.id == fromId || message.id == question?.id || message.id == serverQuestion?.id || message.id == serverReply.id ||
                                        (!message.isUser && message.seq != null && (message.seq ?: 0) >= (serverReply.seq ?: Int.MAX_VALUE))
                                }
                                messages = messages.filterNot { it.local }
                                messages = messages + listOfNotNull(serverQuestion) + serverReply
                                loaded = true
                                // Stop was pressed before the server named the reply: it is stopped now.
                                if (stopWanted) scope.launch { stop() }
                            }
                        }
                        is InboxOlfatoEvent.tool -> patch(replyId) { message ->
                            val tool = event.tool
                            val index = message.tools.indexOfFirst { it.id == tool.id }
                            message.apply { tools = if (index >= 0) tools.toMutableList().also { it[index] = tool } else tools + tool }
                        }
                        is InboxOlfatoEvent.text -> patch(replyId) { message ->
                            message.apply {
                                content += event.text
                                status = InboxOlfatoMessage.Status.streaming
                            }
                        }
                        is InboxOlfatoEvent.replace -> patch(replyId) { message ->
                            message.apply {
                                content = event.text
                                status = if (event.text.isEmpty()) InboxOlfatoMessage.Status.thinking else InboxOlfatoMessage.Status.streaming
                            }
                        }
                        is InboxOlfatoEvent.done -> {
                            finished = true
                            event.chat?.let { chat = it }
                            val replyRow = event.reply
                            if (replyRow != null) {
                                val reply = InboxOlfatoMessage(replyRow)
                                val links = event.links
                                if (!links.isNullOrEmpty()) reply.links = links
                                patch(replyId) { reply }
                            } else {
                                patch(replyId) { it.apply { status = InboxOlfatoMessage.Status.done } }
                            }
                        }
                        is InboxOlfatoEvent.error -> {
                            finished = true
                            val replyRow = event.reply
                            if (replyRow != null) {
                                val reply = InboxOlfatoMessage(replyRow)
                                patch(replyId) { message ->
                                    val toolsSoFar = message.tools.filter { it.status != "running" }
                                    reply.copy().apply {
                                        if (tools.isEmpty()) tools = toolsSoFar
                                        status = if (reply.status == InboxOlfatoMessage.Status.stopped) InboxOlfatoMessage.Status.stopped else InboxOlfatoMessage.Status.error
                                        errorCode = if (reply.status == InboxOlfatoMessage.Status.stopped) null else event.code
                                    }
                                }
                            } else {
                                // Not kept (the writer crashed, the save failed, or the chat was deleted meanwhile).
                                markFailed(replyId, InboxOlfatoRefusal(status = 200, code = event.code, data = emptyMap()))
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                end(token)
                return
            } catch (error: Throwable) {
                failure = InboxOlfatoRefusal.from(error)
            }
            if (finished || !currentCoroutineContext().isActive) break
            // A refusal is an answer: asking again would get the same one.
            if (failure != null && failure.code != "network") break
            if (attempt == 0 && resend) {
                try { delay(600) } catch (cancelled: CancellationException) { /* Swift `try?`: looked at below */ }
            }
        }

        if (!currentCoroutineContext().isActive) {
            end(token)
            return
        }
        if (!finished) {
            val refused = failure
            if (refused != null && refused.code != "network") {
                if (!startedHere && refused.isForm && question != null) {
                    // Nothing was kept: the question goes back into the box, and the box says why.
                    messages = messages.filterNot { it.id == question.id || it.id == replyId }
                    if (chat == null) localTitle = null
                    formRefusal = InboxFormRefusal(sentence = InboxOlfatoRefusal.sentence(refused.code, refused.data), question = question.content)
                } else if (stopWanted && !startedHere) {
                    markStopped(replyId)
                } else {
                    markFailed(replyId, refused)
                }
                note(refused)
            } else if (startedHere) {
                // The connection dropped; the reply may still be finishing.
                loadMessages()
            } else if (stopWanted) {
                markStopped(replyId)
            } else {
                markFailed(replyId, InboxOlfatoRefusal.network())
            }
        }
        end(token)
        announceDataChanged()
    }

    /** Answers the last question again, in place of the reply it got. */
    fun retry() {
        val last = messages.lastOrNull() ?: return
        if (busy || last.isUser) return
        val question = messages.lastOrNull { it.isUser }
        // A question that never reached the server is sent again as itself.
        val pendingId = chatId ?: newId
        if (last.local && question != null && question.local && pendingId != null) {
            patch(last.id) { message ->
                message.apply {
                    status = InboxOlfatoMessage.Status.thinking
                    errorCode = null
                    errorData = emptyMap()
                    content = ""
                    tools = emptyList()
                }
            }
            val exists = chat != null
            val replyId = last.id
            begin { token ->
                send(chatId = pendingId, exists = exists, question = question, replyId = replyId, token = token)
            }
            return
        }
        val id = chat?.id ?: return
        val replyId = "reply-${UUID.randomUUID().toString().lowercase()}"
        messages = messages.filterNot { it.id == last.id } + InboxOlfatoMessage(id = replyId, isUser = false, content = "", status = InboxOlfatoMessage.Status.thinking, local = true)
        val regenerate = path(id, "/regenerate")
        val language = language
        begin { token ->
            follow(path = regenerate, body = mapOf("language" to language), question = null, shownReplyId = replyId, resend = false, token = token)
        }
    }

    /** Stops the reply. */
    suspend fun stop() {
        val id = chat?.id
        if (id == null || (live && !started)) {
            // The server hasn't named the reply yet: it is stopped the moment it is.
            if (live) stopWanted = true
            return
        }
        try {
            val answer = API.post<InboxStopAnswer>(path(id, "/stop"))
            if (live) {
                task?.cancel()
                end(runToken)
            }
            answer.reply?.let { reply ->
                messages = messages.filterNot { (it.local && !it.isUser) || it.id == reply.id } + InboxOlfatoMessage(reply)
            }
            loadMessages()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            note(InboxOlfatoRefusal.from(failure))
        }
    }

    // MARK: Rating, renaming, deleting

    suspend fun rate(messageId: String, rating: String?, reason: String?) {
        val id = chatId ?: return
        val before = messages.firstOrNull { it.id == messageId }?.feedback
        patch(messageId) { it.apply { feedback = rating?.let { r -> InboxOlfatoFeedback(rating = r, reason = reason) } } }
        val endpoint = path(id, "/messages/$messageId/feedback")
        try {
            if (rating != null) {
                // The reason is left out, never null, for a thumbs up.
                val body = mutableMapOf<String, Any?>("rating" to rating)
                if (reason != null) body["reason"] = reason
                API.send("PUT", endpoint, body)
            } else {
                API.send("DELETE", endpoint)
            }
        } catch (failure: Throwable) {
            patch(messageId) { it.apply { feedback = before } }
            throw failure
        }
    }

    suspend fun rename(title: String) {
        val id = chat?.id ?: return
        val envelope = API.patch<InboxOlfatoChatEnvelope>(path(id), mapOf("title" to title))
        chat = envelope.chat
        announceDataChanged()
    }

    suspend fun delete() {
        val id = chat?.id ?: return
        try {
            API.send("DELETE", path(id))
        } catch (error: APIError) {
            // Gone already is what was asked for.
            if (!(error.code == "olfato_chat_not_found" || error.status == 404)) throw error
        }
        leave()
        gone = true
        announceDataChanged()
    }
}
