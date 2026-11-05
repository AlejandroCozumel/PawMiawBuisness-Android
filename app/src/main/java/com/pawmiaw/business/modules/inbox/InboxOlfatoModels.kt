package com.pawmiaw.business.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.text.Normalizer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

//
//  InboxOlfatoModels.kt (iOS InboxOlfatoModels.swift)
//
//  Olfato AI's chat for businesses as the server keeps it (paw-miaw-backend
//  docs/olfato-business-chat-plan.md §10, routes/provider-olfato.ts; web
//  components/app/olfato-business/olfato-business-api.ts and
//  hooks/use-olfato-business.ts): the panel of what this person can ask
//  about, the chats, their messages, and a reply as it arrives over
//  server-sent events. The server decides who may ask and what a reply is;
//  nothing here decides anything.
//

// MARK: - The panel (GET /provider-olfato/viewer)

/** A text the server words in both languages. */
@Serializable
data class InboxOlfatoWords(
    val es: String? = null,
    val en: String? = null,
) {
    val text: String
        get() = if (LocalizationManager.currentLanguage == AppLanguage.spanish) (es ?: en ?: "") else (en ?: es ?: "")

    /** In `language`; the other when that one is missing (lib/olfato-business-copy.ts `olfatoWordsIn`). */
    fun text(language: InboxOlfatoLanguage): String {
        val first = if (language == InboxOlfatoLanguage.es) es else en
        val other = if (language == InboxOlfatoLanguage.es) en else es
        return first.nonEmpty ?: other ?: ""
    }
}

// MARK: - The language a reply is written in

/**
 * The language one reply is written in, as the server decides it (paw-miaw-backend
 * lib/olfato-business/chat/language.ts, kept word for word in the web's lib/olfato-business-copy.ts
 * `olfatoReplyLanguage`): the one its question is written in, else the app's. What belongs to the
 * reply (its "Revisando…", lock chips and "Ver en …") follows it, so an English answer ends with
 * "See in Finances" even in the Spanish app; the buttons around it are the app's.
 */
enum class InboxOlfatoLanguage(val rawValue: String) {
    es("es"), en("en");

    /** This module's words in this language (then the shared ones), read once. */
    private val words: Map<String, String>
        get() {
            tables[this]?.let { return it }
            val table = mutableMapOf<String, String>()
            for (name in listOf("Inbox", "Localizable")) {
                val text = runCatching {
                    AppContext.context.assets.open("i18n/$rawValue/$name.json").bufferedReader().use { it.readText() }
                }.getOrNull() ?: continue
                val obj = runCatching { API.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
                for ((key, value) in obj) {
                    val words = (value as? JsonPrimitive)?.content ?: continue
                    table.putIfAbsent(key, words)
                }
            }
            tables[this] = table
            return table
        }

    /** A sentence of the chat in this language (the key is the English, as with `L`). */
    fun text(key: String): String {
        words[key]?.let { return it }
        // "Open@olfato" → "Open": a key's context is never shown.
        val at = key.lastIndexOf('@')
        if (at <= 0) return key
        val context = key.substring(at + 1)
        if (context.isEmpty() || !context.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return key
        return key.substring(0, at)
    }

    fun format(key: String, vararg arguments: Any?): String {
        val format = text(key)
        val locale = if (this == es) AppLanguage.spanish.locale else AppLanguage.english.locale
        return runCatching { String.format(locale, LocalizationManager.javaFormat(format), *arguments) }.getOrElse { format }
    }

    companion object {
        /** The app's language, as the chat names it. */
        val app: InboxOlfatoLanguage
            get() = if (LocalizationManager.currentLanguage == AppLanguage.spanish) es else en

        private val spanishWords: Set<String> = "qué que cuánto cuánta cuántos cuántas cuanto cuantos como cómo cuál cual cuáles quién quien quiénes quienes dónde donde cuándo cuando el la los las lo de del al y o en es son está están estan hay hubo tiene tienen tenemos tengo tuvimos hoy ayer mañana semana mes año por para con sin una un unos unas mi mis su sus tu tus me te le nos dame dime muestra muéstrame puedes podrías hola gracias cobramos vendimos facturamos vino vinieron debe deben quedó sin pagar cita citas mascota mascotas dueña dueño cliente clientes también sí no"
            .split(" ").filter { it.isNotEmpty() }.toSet()
        private val englishWords: Set<String> = "what whats how much many who whom which when where why is are was were did do does done we our us you your i my me this that these those today yesterday tomorrow week month year the a an of for with without in on at to from by bill billed sales sold show give tell list owe owes owed has have had any pet pets client clients visit visits appointment please hi hello thanks yes and or"
            .split(" ").filter { it.isNotEmpty() }.toSet()
        private val letters: Set<Char> = "abcdefghijklmnopqrstuvwxyzáéíóúüñ".toSet()
        private val spanishMarks: Set<Char> = "¿¡ñáéíóú".toSet()

        private val tables = ConcurrentHashMap<InboxOlfatoLanguage, Map<String, String>>()

        /** The language a question is written in; a tie is the app's. */
        fun of(question: String, app: InboxOlfatoLanguage = Companion.app): InboxOlfatoLanguage {
            // Composed accents, as Swift compares characters.
            val text = Normalizer.normalize(question.lowercase(), Normalizer.Form.NFC)
            var es = if (text.any { it in spanishMarks }) 2 else 0
            var en = 0
            val word = StringBuilder()
            fun count() {
                val w = word.toString()
                if (w in spanishWords) es += 1
                if (w in englishWords) en += 1
                word.clear()
            }
            for (character in text) {
                if (character in letters) word.append(character) else if (word.isNotEmpty()) count()
            }
            if (word.isNotEmpty()) count()
            if (es == en) return app
            return if (es > en) InboxOlfatoLanguage.es else InboxOlfatoLanguage.en
        }

        /**
         * The reply at `index`: its question's language (the question before it), else — the question is on
         * an older page not read yet — the reply's own words (olfato-business-thread.tsx `replyLanguageOf`).
         */
        fun ofReply(messages: List<InboxOlfatoMessage>, index: Int): InboxOlfatoLanguage {
            var at = minOf(index, messages.size) - 1
            while (at >= 0) {
                if (messages[at].isUser) return of(messages[at].content)
                at -= 1
            }
            return of(if (index < messages.size) messages[index].content else "")
        }
    }
}

@Serializable
data class InboxOlfatoArea(
    val key: String,
    val label: InboxOlfatoWords? = null,
    val open: Boolean? = null,
    val offered: Boolean? = null,
    val reason: InboxOlfatoWords? = null,
    val notes: List<InboxOlfatoWords>? = null,
) {
    val id: String get() = key
}

@Serializable
data class InboxOlfatoChatGateStatus(
    val available: Boolean? = null,
    val workspaceOn: Boolean? = null,
    val noticeOutdated: Boolean? = null,
    val acknowledged: Boolean? = null,
    val canSend: Boolean? = null,
    val refusal: String? = null,
    val maxMessageChars: Int? = null,
)

@Serializable
data class InboxOlfatoPanel(
    val canChat: Boolean? = null,
    val moneyLevel: Int? = null,
    val areas: List<InboxOlfatoArea>? = null,
    val scope: List<InboxOlfatoWords>? = null,
    val today: String? = null,
    val timeZone: String? = null,
    val chat: InboxOlfatoChatGateStatus? = null,
) {
    /** The offered areas, open first (Swift's `(open:closed:)` tuple). */
    data class OfferedAreas(val open: List<InboxOlfatoArea>, val closed: List<InboxOlfatoArea>)

    val offeredAreas: OfferedAreas
        get() {
            val offered = (areas ?: emptyList()).filter { it.offered == true }
            return OfferedAreas(offered.filter { it.open == true }, offered.filter { it.open != true })
        }
}

/** What stands before the chat (lib/olfato-business-refusals.ts `olfatoChatGate`). */
enum class InboxOlfatoGate {
    loading, error, unavailable, privateSession, offForYou, notice, off, ready;

    /** Earlier chats stay readable; nothing new can be asked. */
    val readable: Boolean get() = this == ready || this == off || this == offForYou || this == unavailable

    val sentence: String
        get() = when (this) {
            notice -> InboxOlfatoRefusal.sentence("olfato_notice_required")
            error -> L("Olfato AI could not be loaded.")
            unavailable -> InboxOlfatoRefusal.sentence("olfato_unavailable")
            privateSession -> InboxOlfatoRefusal.sentence("olfato_private")
            offForYou -> InboxOlfatoRefusal.sentence("olfato_chat_off_for_you")
            off -> InboxOlfatoRefusal.sentence("olfato_off")
            loading, ready -> ""
        }

    companion object {
        fun of(chat: InboxOlfatoChatGateStatus?, failed: Boolean): InboxOlfatoGate {
            if (chat == null) return if (failed) error else loading
            return when (chat.refusal) {
                "olfato_unavailable" -> unavailable
                "olfato_private" -> privateSession
                "olfato_chat_off_for_you" -> offForYou
                "olfato_notice_required" -> notice
                "olfato_off" -> if (chat.noticeOutdated == true) notice else off
                else -> if (chat.canSend == true) ready else unavailable
            }
        }
    }
}

/** Reads the panel; both Olfato AI screens own one (`remember { InboxOlfatoViewer() }`). */
class InboxOlfatoViewer {
    var panel: InboxOlfatoPanel? by mutableStateOf(null)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    val gate: InboxOlfatoGate get() = InboxOlfatoGate.of(panel?.chat, failed)

    /**
     * "Preguntar a Olfato" belongs on a pet's chart (web `useOlfatoChatEntry().shown`): the chat is
     * available and on for the business. The caller also checks `Workspace.shared.can(Permission.olfatoChat)`.
     */
    val showsEntry: Boolean get() = panel?.chat?.available == true && panel?.chat?.workspaceOn == true

    suspend fun load() {
        try {
            panel = API.get<InboxOlfatoPanel>("/provider-olfato/viewer")
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = panel == null
        }
    }
}

// MARK: - Chats and messages

/** Swift `Date.distantPast`. */
private val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")

@Serializable
data class InboxOlfatoChat(
    val id: String,
    val way: String? = null,
    val petId: String? = null,
    val petName: String? = null,
    val title: String? = null,
    val messageCount: Int? = null,
    val lastMessageAt: String? = null,
    val deleteAfter: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val replying: Boolean? = null,
) {
    val sortDate: Instant get() = DateText.parse(lastMessageAt ?: updatedAt) ?: distantPast
    val displayTitle: String get() = title.nonEmpty ?: L("New chat")
    /** About a pet that is no longer one this person can open. */
    val petGone: Boolean get() = way == "pet" && petId == null

    val about: String
        get() {
            if (way == "pet") {
                return if (petGone) L("About a pet you can no longer open") else LF("About %@", petName.nonEmpty ?: L("a pet"))
            }
            return L("About your business")
        }
}

@Serializable
data class InboxOlfatoChatList(
    val chats: List<InboxOlfatoChat>? = null,
    val nextCursor: String? = null,
)

@Serializable
data class InboxOlfatoChatEnvelope(
    val chat: InboxOlfatoChat,
)

@Serializable
data class InboxOlfatoTool(
    val id: String? = null,
    val name: String? = null,
    val area: String? = null,
    val areaLabel: InboxOlfatoWords? = null,
    val status: String? = null,
) {
    val areaName: String get() = areaLabel?.text.nonEmpty ?: L("the business")

    /** The area's name in the reply's language. */
    fun areaName(language: InboxOlfatoLanguage): String =
        areaLabel?.text(language).nonEmpty ?: language.text("the business")
}

/** A screen an answer came from ("Ver en …"). */
@Serializable
data class InboxOlfatoLink(
    val to: String? = null,
    val params: Map<String, String>? = null,
    val search: Map<String, JSONValue>? = null,
    val label: InboxOlfatoWords? = null,
) {
    /** Only the provider's own screens; `$clientId` filled from the params. */
    val route: ProviderRoute?
        get() {
            var path = to ?: return null
            if (!path.startsWith("/provider") || path.contains("..")) return null
            for ((key, value) in params ?: emptyMap()) path = path.replace("$$key", value)
            if (path.contains("$")) return null
            val query = mutableMapOf<String, String>()
            for ((key, value) in search ?: emptyMap()) {
                // A string or a number, as Swift's `JSONValue.string` reads it (a true/false is not one).
                val primitive = value as? JsonPrimitive ?: continue
                if (!primitive.isString && primitive.booleanOrNull != null) continue
                primitive.string?.let { query[key] = it }
            }
            return InboxRoutes.route(path, query)
        }
}

@Serializable
data class InboxOlfatoFeedback(
    val rating: String? = null,
    val reason: String? = null,
)

@Serializable
data class InboxOlfatoMessageRow(
    val id: String,
    val seq: Int? = null,
    val role: String? = null,
    val content: String? = null,
    val status: String? = null,
    val errorCode: String? = null,
    val truncated: Boolean? = null,
    val clientMessageId: String? = null,
    val tools: List<InboxOlfatoTool>? = null,
    val links: List<InboxOlfatoLink>? = null,
    val feedback: InboxOlfatoFeedback? = null,
)

@Serializable
data class InboxOlfatoMessagesPage(
    val messages: List<InboxOlfatoMessageRow>? = null,
    val hasOlder: Boolean? = null,
)

/** A message as the thread shows it. What is on screen before the server answered is `local`. */
data class InboxOlfatoMessage(
    var id: String,
    var isUser: Boolean,
    var content: String,
    var status: Status = Status.done,
    var local: Boolean = false,
    var clientMessageId: String? = null,
    var errorCode: String? = null,
    var errorData: Map<String, JSONValue> = emptyMap(),
    var truncated: Boolean = false,
    var feedback: InboxOlfatoFeedback? = null,
    var tools: List<InboxOlfatoTool> = emptyList(),
    var links: List<InboxOlfatoLink> = emptyList(),
    var seq: Int? = null,
) {
    enum class Status(val rawValue: String) { thinking("thinking"), streaming("streaming"), done("done"), stopped("stopped"), error("error") }

    constructor(row: InboxOlfatoMessageRow) : this(
        id = row.id,
        isUser = row.role == "user",
        content = row.content ?: "",
        status = if (row.role == "user") Status.done else when (row.status) {
            "streaming" -> if ((row.content ?: "").isEmpty()) Status.thinking else Status.streaming
            "stopped" -> Status.stopped
            "error" -> Status.error
            else -> Status.done
        },
        clientMessageId = row.clientMessageId,
        errorCode = if (row.role != "user" && row.status == "error") (row.errorCode ?: "internal") else null,
        truncated = if (row.role != "user") (row.truncated ?: false) else false,
        feedback = if (row.role != "user") row.feedback else null,
        tools = if (row.role != "user") (row.tools ?: emptyList()) else emptyList(),
        links = if (row.role != "user") (row.links ?: emptyList()) else emptyList(),
        seq = row.seq,
    )

    val arriving: Boolean get() = status == Status.thinking || status == Status.streaming
}

// MARK: - Refusals (lib/olfato-business-refusals.ts)

class InboxOlfatoRefusal(
    val status: Int,
    val code: String,
    val data: Map<String, JSONValue>,
) : Exception(code) {
    val isGate: Boolean get() = code in gateCodes
    val isForm: Boolean get() = code in formCodes

    companion object {
        val gateCodes: Set<String> = setOf("olfato_unavailable", "olfato_private", "olfato_chat_off_for_you", "olfato_off", "olfato_notice_required")
        val formCodes: Set<String> = setOf("olfato_too_long", "olfato_empty")

        fun network(): InboxOlfatoRefusal = InboxOlfatoRefusal(0, "network", emptyMap())

        /** From a failed answer's body: `{ error, code, retryAfterSeconds, maxChars… }`. */
        fun from(body: ByteArray, status: Int, retryAfter: String?): InboxOlfatoRefusal {
            val data = (runCatching { API.json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: emptyMap()).toMutableMap()
            if (!data.containsKey("retryAfterSeconds")) {
                val wait = retryAfter?.toDoubleOrNull()
                if (wait != null && wait > 0) data["retryAfterSeconds"] = JsonPrimitive(wait)
            }
            val code = data["code"].string ?: (if (status == 401) "unauthorized" else "olfato_failed")
            return InboxOlfatoRefusal(status, code, data)
        }

        fun from(error: Throwable): InboxOlfatoRefusal {
            if (error is InboxOlfatoRefusal) return error
            if (error is APIError && !error.isOffline) {
                return InboxOlfatoRefusal(error.status, error.code ?: "olfato_failed", mapOf("error" to JsonPrimitive(error.message)))
            }
            return network()
        }

        /** "en 2 h", "en 15 min". */
        fun wait(seconds: Double): String {
            val minutes = maxOf(1, ceil(seconds / 60).toInt())
            if (minutes < 60) return LF("%d min@wait", minutes)
            return LF("%d h@wait", ceil(minutes.toDouble() / 60).toInt())
        }

        /** What the person reads for a refusal or a reply's error code. */
        fun sentence(code: String?, data: Map<String, JSONValue> = emptyMap()): String = when (code) {
            "olfato_too_long" -> data["maxChars"].int?.let { LF("That question is too long. Keep it under %d characters.", it) }
                ?: L("That question is too long. Shorten it and try again.")
            "olfato_limit" -> data["retryAfterSeconds"].double?.let { LF("You have asked a lot of questions today. Try again in %@.", wait(it)) }
                ?: L("You have asked a lot of questions today. Try again later.")
            "olfato_unavailable", "provider_configuration" -> L("Olfato AI is not available right now.")
            "olfato_private" -> L("Olfato AI chats are private and are not available in a support session.")
            "olfato_chat_off_for_you" -> L("Your access doesn’t include Olfato AI. Ask an owner or an admin.")
            "olfato_off" -> L("Olfato AI is turned off for this business.")
            "olfato_notice_required" -> L("Your business has to accept the Olfato AI notice.")
            "olfato_empty" -> L("Write a question first.")
            "olfato_busy" -> L("Olfato AI is still answering. Wait for it to finish.")
            "olfato_chat_not_found" -> L("That chat is not here any more.")
            "olfato_pet_not_found" -> L("That pet is not one you can open.")
            "olfato_pet_required" -> L("Choose the pet this chat is about.")
            "olfato_nothing_to_answer" -> L("There is no question to answer in this chat yet.")
            "olfato_not_rateable" -> L("Only a finished reply can be rated.")
            "olfato_invalid" -> L("That request could not be read.")
            "network" -> L("Not sent. Check your connection and try again.")
            "provider_unavailable" -> L("Olfato AI could not answer right now. Try again in a moment.")
            "provider_rate_limited" -> L("Olfato AI is very busy right now. Try again in a moment.")
            "refused" -> L("I can’t help with that request. Try asking it another way.")
            "empty_reply" -> L("No reply came back. Try again.")
            "interrupted" -> L("The reply was interrupted. Answer again.")
            "no_access" -> L("Your access changed before the answer. Ask again.")
            else -> L("I could not answer. Try again.")
        }

        /** Asking again won't change these. */
        fun retryHelps(code: String?): Boolean {
            if (code == null) return true
            return code != "refused" && code != "olfato_chat_not_found" && code != "olfato_pet_not_found" && code !in gateCodes
        }
    }
}

// MARK: - A reply as it arrives

/** One server-sent event of a question or "answer again": `start`, any `tool`, `text`, `replace`, then `done` or `error`. */
sealed class InboxOlfatoEvent {
    data class start(val chat: InboxOlfatoChat?, val question: InboxOlfatoMessageRow?, val reply: InboxOlfatoMessageRow?) : InboxOlfatoEvent()
    data class tool(val tool: InboxOlfatoTool) : InboxOlfatoEvent()
    data class text(val text: String) : InboxOlfatoEvent()
    data class replace(val text: String) : InboxOlfatoEvent()
    data class done(val chat: InboxOlfatoChat?, val reply: InboxOlfatoMessageRow?, val links: List<InboxOlfatoLink>?) : InboxOlfatoEvent()
    data class error(val code: String, val reply: InboxOlfatoMessageRow?) : InboxOlfatoEvent()
}

@Serializable private data class InboxStartData(val chat: InboxOlfatoChat? = null, val question: InboxOlfatoMessageRow? = null, val reply: InboxOlfatoMessageRow? = null)
@Serializable private data class InboxTextData(val text: String? = null)
@Serializable private data class InboxDoneData(val chat: InboxOlfatoChat? = null, val reply: InboxOlfatoMessageRow? = null, val links: List<InboxOlfatoLink>? = null)
@Serializable private data class InboxErrorData(val code: JSONValue? = null, val reply: InboxOlfatoMessageRow? = null)

/** Swift `trimmingCharacters(in: .whitespaces)`: spaces and tabs, not line breaks. */
private fun String.trimSpaces(): String = trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

object InboxOlfatoStream {
    /** The API's client, with room for a long answer between bytes (Swift `timeoutInterval = 120`). */
    private val client: OkHttpClient by lazy { API.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() }

    /**
     * POSTs a question (or "answer again") and calls `onEvent` (on the main thread) for each event as it
     * arrives, reading the response's bytes (`text/event-stream`). Returns when the stream ends; a refusal
     * is thrown as `InboxOlfatoRefusal`, a dropped connection as its `network` code.
     */
    suspend fun run(path: String, body: Map<String, Any?>, onEvent: (InboxOlfatoEvent) -> Unit) {
        val request = API.request("POST", path, emptyMap(), API.jsonBody(body)).newBuilder()
            .header("Accept", "text/event-stream")
            .build()

        val call = client.newCall(request)
        val response: Response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(InboxOlfatoRefusal.network())
                }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

        response.use { http ->
            if (http.code !in 200..299) {
                val data = withContext(Dispatchers.IO) { runCatching { http.body?.bytes() }.getOrNull() ?: ByteArray(0) }
                if (http.code == 401) AppEvents.postSessionMayHaveExpired()
                throw InboxOlfatoRefusal.from(data, http.code, http.header("Retry-After"))
            }
            val source = http.body?.source() ?: throw InboxOlfatoRefusal.network()
            coroutineScope {
                // A blocking read doesn't hear the coroutine's cancellation: cancelling the call ends it.
                val watcher = launch {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    read(source, onEvent)
                } finally {
                    watcher.cancel()
                }
            }
        }
    }

    private suspend fun read(source: BufferedSource, onEvent: (InboxOlfatoEvent) -> Unit) {
        var name = ""
        var payload = ""
        suspend fun flush() {
            val event = if (name.isNotEmpty()) decode(name, payload) else null
            name = ""
            payload = ""
            if (event != null) withContext(Dispatchers.Main) { onEvent(event) }
        }
        try {
            withContext(Dispatchers.IO) {
                while (true) {
                    // A line ends at a LF; what is left without one when the stream ends is dropped.
                    val end = source.indexOf('\n'.code.toByte())
                    if (end == -1L) break
                    var text = source.readUtf8(end)
                    source.skip(1)
                    if (text.endsWith("\r")) text = text.dropLast(1)
                    if (text.isEmpty()) {
                        flush()
                    } else if (text.startsWith("event:")) {
                        name = text.drop(6).trimSpaces()
                    } else if (text.startsWith("data:")) {
                        payload += text.drop(5).trimSpaces()
                    }
                }
                flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw InboxOlfatoRefusal.network()
        }
    }

    private fun decode(name: String, payload: String): InboxOlfatoEvent? {
        val data = payload.ifEmpty { "{}" }
        val json = API.json
        return when (name) {
            "start" -> {
                val start = runCatching { json.decodeFromString(InboxStartData.serializer(), data) }.getOrNull() ?: return null
                InboxOlfatoEvent.start(start.chat, start.question, start.reply)
            }
            "tool" -> {
                val tool = runCatching { json.decodeFromString(InboxOlfatoTool.serializer(), data) }.getOrNull() ?: return null
                if (tool.id == null || tool.name == null || (tool.status ?: "") !in listOf("running", "done", "closed", "failed")) return null
                InboxOlfatoEvent.tool(tool)
            }
            "text" -> {
                val text = runCatching { json.decodeFromString(InboxTextData.serializer(), data) }.getOrNull()?.text ?: return null
                InboxOlfatoEvent.text(text)
            }
            "replace" -> InboxOlfatoEvent.replace(runCatching { json.decodeFromString(InboxTextData.serializer(), data) }.getOrNull()?.text ?: "")
            "done" -> {
                val done = runCatching { json.decodeFromString(InboxDoneData.serializer(), data) }.getOrNull()
                InboxOlfatoEvent.done(done?.chat, done?.reply, done?.links)
            }
            "error" -> {
                val failure = runCatching { json.decodeFromString(InboxErrorData.serializer(), data) }.getOrNull()
                InboxOlfatoEvent.error(failure?.code.string ?: "internal", failure?.reply)
            }
            else -> null
        }
    }
}
