package com.pawmiaw.business

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

//
//  Every request to the backend goes through `API` (iOS APIClient.swift). It signs
//  the request with the session's Bearer token, asks for the app's language, and
//  turns a failed answer into an `APIError` carrying the server's own sentence
//  (and, for a refused form, the sentence for each field).
//
//      val page: Page<Client> = API.get("/provider-clients", mapOf("limit" to "20", "offset" to "0"))
//      val saved: Client = API.post("/provider-clients", mapOf("name" to name, "phone" to null))
//      val saved: Client = API.postJson("/provider-clients", someSerializable)   // Swift post(_:json:)
//      API.send("DELETE", "/provider-clients/$id")
//
//  Bodies given as `Map<String, Any?>` drop nothing: a null value is sent as JSON
//  null, which is how the backend clears a field. Leave the key out to keep it.
//

class APIError(
    val status: Int,
    override val message: String,
    val code: String?,
    val fields: Map<String, String>,
    /** The whole refusal as the server sent it (a visit's conflicts, the hours problem, a fee to decide…). */
    val body: JsonElement? = null,
) : Exception(message) {
    /** The request never reached the server (offline, timed out). */
    val isOffline: Boolean get() = status == 0
    /** Refused by the server (4xx): retrying the same request cannot work. */
    val isRefusal: Boolean get() = status in 400..499

    /** The sentence for one field of a refused form. */
    fun field(vararg names: String): String? = names.firstNotNullOfOrNull { fields[it]?.takeIf(String::isNotEmpty) }

    companion object {
        fun offline() = APIError(0, L("You're offline. Check your connection and try again."), "offline", emptyMap())
    }
}

/** What a screen shows after a failed action: the server's sentence when it refused, else the fallback. */
fun errorText(error: Throwable, fallback: String = L("Something went wrong. Try again.")): String {
    // A debug build says in the log what went wrong behind the sentence shown.
    if (BuildConfig.DEBUG) android.util.Log.w("PawMiawError", "shown as an error: $error", error)
    if (error is APIError) {
        if (error.isOffline) return error.message
        if (error.message.isNotEmpty()) return error.message
    }
    return fallback
}

/** An answer whose body the screen ignores. */
@Serializable
object EmptyResponse

// MARK: - Any JSON (iOS `JSONValue`)

/** Any JSON value, for answers whose shape a screen only reads in part (iOS `JSONValue`). */
typealias JSONValue = JsonElement

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
val JsonElement?.string: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { if (it.isString) it.content else it.content }
val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull
val JsonElement?.array: List<JsonElement>? get() = this as? JsonArray
val JsonElement?.obj: Map<String, JsonElement>? get() = this as? JsonObject
val JsonElement?.isNull: Boolean get() = this == null || this is JsonNull

// MARK: - The client

object API {
    /** Lenient JSON, the way the iOS decoders read the server (unknown keys ignored). */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", APIConfig.userAgent).build()) }
        if (BuildConfig.DEBUG) builder.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        builder.build()
    }

    // MARK: Verbs

    suspend inline fun <reified T> get(path: String, query: Map<String, String?> = emptyMap()): T {
        val bytes = data("GET", path, query, null)
        // A long list is read off the main thread.
        return if (bytes.size > 16_384) kotlinx.coroutines.withContext(Dispatchers.Default) { decode<T>(bytes) } else decode(bytes)
    }

    suspend inline fun <reified T> post(path: String, body: Map<String, Any?> = emptyMap(), query: Map<String, String?> = emptyMap()): T =
        decode(data("POST", path, query, jsonBody(body)))

    /** Swift `post(_:json:)`: a serializable body. */
    suspend inline fun <reified T, reified B> postJson(path: String, json: B, query: Map<String, String?> = emptyMap()): T =
        decode(data("POST", path, query, encode(json)))

    suspend inline fun <reified T> put(path: String, body: Map<String, Any?> = emptyMap()): T =
        decode(data("PUT", path, emptyMap(), jsonBody(body)))

    suspend inline fun <reified T, reified B> putJson(path: String, json: B): T =
        decode(data("PUT", path, emptyMap(), encode(json)))

    suspend inline fun <reified T> patch(path: String, body: Map<String, Any?> = emptyMap()): T =
        decode(data("PATCH", path, emptyMap(), jsonBody(body)))

    suspend inline fun <reified T, reified B> patchJson(path: String, json: B): T =
        decode(data("PATCH", path, emptyMap(), encode(json)))

    suspend inline fun <reified T> delete(path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()): T =
        decode(data("DELETE", path, query, body?.let { jsonBody(it) }))

    /** A request whose answer is not read. */
    suspend fun send(method: String, path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()) {
        data(method, path, query, body?.let { jsonBody(it) })
    }

    /** One file and a few fields, as multipart/form-data. */
    suspend inline fun <reified T> upload(
        path: String, method: String = "POST", fileField: String = "file", fileName: String, mimeType: String,
        fileData: ByteArray, fields: Map<String, String> = emptyMap(),
    ): T = multipart(path, method, fields.toList(), listOf(UploadFile(fileField, fileName, mimeType, fileData)))

    /** A file part for `multipart`. */
    class UploadFile(val field: String, val fileName: String, val mimeType: String, val data: ByteArray)

    /** Several fields (repeatable) and files, as multipart/form-data. */
    suspend inline fun <reified T> multipart(
        path: String, method: String = "POST", fields: List<Pair<String, String>> = emptyList(), files: List<UploadFile>,
    ): T {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((name, value) in fields) builder.addFormDataPart(name, value)
        for (file in files) builder.addFormDataPart(file.field, file.fileName, file.data.toRequestBody(file.mimeType.toMediaType()))
        val body = builder.build()
        val buffer = okio.Buffer().also { body.writeTo(it) }
        return decode(data(method, path, emptyMap(), buffer.readByteArray(), body.contentType().toString()))
    }

    /**
     * Downloads a file the server makes (a PDF, a CSV) with the session into a cache
     * file named for people ("Recibo V-000123.pdf"), for viewing or sharing.
     */
    suspend fun download(path: String, query: Map<String, String?> = emptyMap(), fileName: String, ext: String = "pdf"): File {
        val trimmed = if (path.startsWith("/api/")) path.drop(4) else path
        val bytes = data("GET", trimmed, query, null)
        val safe = fileName.replace(Regex("[/\\\\:?%*|\"<>]"), "-").ifBlank { "PawMiaw" }
        val name = if (safe.lowercase().endsWith(".$ext")) safe else "$safe.$ext"
        return withContext(Dispatchers.IO) {
            val folder = File(AppContext.context.cacheDir, "exports/${UUID.randomUUID()}").apply { mkdirs() }
            File(folder, name).apply { writeBytes(bytes) }
        }
    }

    // MARK: Plumbing

    fun url(path: String, query: Map<String, String?> = emptyMap()): String {
        val trimmed = path.removePrefix("/")
        val parts = trimmed.split("?", limit = 2)
        val builder = (APIConfig.baseURL.removeSuffix("/") + "/" + parts[0]).toHttpUrl().newBuilder()
        if (parts.size > 1) {
            for (pair in parts[1].split("&").filter { it.isNotEmpty() }) {
                val (k, v) = pair.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                builder.addEncodedQueryParameter(k, v)
            }
        }
        for ((key, value) in query.toSortedMap()) if (value != null) builder.addQueryParameter(key, value)
        return builder.build().toString()
    }

    fun request(method: String, path: String, query: Map<String, String?> = emptyMap(), body: ByteArray? = null, contentType: String = "application/json"): Request {
        val language = LocalizationManager.currentLanguage.rawValue
        val builder = Request.Builder()
            .url(url(path, query))
            .header("Accept", "application/json")
            .header("Accept-Language", language)
            .header("X-Preferred-Language", language)
        SessionToken.current?.let { builder.header("Authorization", "Bearer $it") }
        val requestBody = body?.toRequestBody(contentType.toMediaType())
            ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(null) else null
        return builder.method(method, requestBody).build()
    }

    suspend fun data(method: String, path: String, query: Map<String, String?>, body: ByteArray?, contentType: String = "application/json"): ByteArray {
        val response = execute(request(method, path, query, body, contentType))
        val bytes = response.body?.bytes() ?: ByteArray(0)
        val status = response.code
        response.close()
        if (status !in 200..299) {
            if (status == 401 && SessionToken.current != null) AppEvents.postSessionMayHaveExpired()
            throw errorFrom(bytes, status)
        }
        return bytes
    }

    /**
     * Runs a request, cancelled with the coroutine. Transport failures are `APIError.offline()`.
     * The answer comes back already read (its body buffered), so callers may read it on any thread.
     */
    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(APIError.offline())
            }
            override fun onResponse(call: Call, response: Response) {
                // The answer is read here, on OkHttp's thread: reading a body on the main thread is
                // refused by Android (NetworkOnMainThreadException) once it is more than a few KB.
                val buffered = try {
                    response.use {
                        val type = it.body?.contentType()
                        val bytes = it.body?.bytes() ?: ByteArray(0)
                        it.newBuilder().body(bytes.toResponseBody(type)).build()
                    }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(APIError.offline())
                    return
                }
                continuation.resume(buffered)
            }
        })
    }

    inline fun <reified T> decode(data: ByteArray): T {
        if (T::class == EmptyResponse::class || T::class == Unit::class) {
            @Suppress("UNCHECKED_CAST")
            return (if (T::class == Unit::class) Unit else EmptyResponse) as T
        }
        val text = if (data.isEmpty()) "{}" else data.toString(Charsets.UTF_8)
        return try {
            json.decodeFromString(serializer<T>(), text)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("API", "decode ${T::class.simpleName} failed: ${e.message}")
            throw APIError(200, L("The server's answer could not be read. Update the app or try again."), "decode_failed", emptyMap())
        }
    }

    inline fun <reified B> encode(value: B): ByteArray = json.encodeToString(serializer<B>(), value).toByteArray()

    fun jsonBody(body: Map<String, Any?>): ByteArray = toJsonElement(body).toString().toByteArray()

    /** Plain values (strings, numbers, booleans, null, lists, maps, JsonElements, dates) as JSON. */
    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is java.time.Instant -> JsonPrimitive(value.toString())
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
        is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
        is Array<*> -> JsonArray(value.map { toJsonElement(it) })
        is Enum<*> -> JsonPrimitive(value.name)
        else -> JsonPrimitive(value.toString())
    }

    fun errorFrom(data: ByteArray, status: Int): APIError {
        val raw = runCatching { json.parseToJsonElement(data.toString(Charsets.UTF_8)) }.getOrNull()
        ServerErrorBody.parse(raw)?.let { return APIError(status, it.error, it.code, it.fields, raw) }
        val fallback = when (status) {
            401 -> L("Your session expired. Please sign in again.")
            403 -> L("Your role can't do this. Ask the owner of the business.")
            404 -> L("This was not found. It may have been removed.")
            429 -> L("Too many tries. Wait a moment and try again.")
            else -> L("Something went wrong. Try again.")
        }
        return APIError(status, fallback, null, emptyMap(), raw)
    }
}

// MARK: - Pages

/**
 * A page of a list the server pages (`limit` + `offset`), in the shapes the provider
 * endpoints answer with: `{ items|data|rows|…, total }` or a bare array.
 */
@Serializable(with = PageSerializer::class)
data class Page<T>(val items: List<T>, val total: Int? = null, val hasMore: Boolean? = null) {
    companion object {
        /** Keys a page's rows come under, tried in order. */
        val itemKeys = listOf("items", "data", "rows", "results", "clients", "appointments", "records", "invoices", "sales", "products", "patients", "pets", "stays", "notifications", "followUps", "members", "entries", "chats", "messages")
    }
}

class PageSerializer<T>(private val itemSerializer: KSerializer<T>) : KSerializer<Page<T>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Page")

    override fun deserialize(decoder: Decoder): Page<T> {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val list = kotlinx.serialization.builtins.ListSerializer(itemSerializer)
        if (element is JsonArray) {
            val items = API.json.decodeFromJsonElement(list, element)
            return Page(items, items.size, false)
        }
        val obj = element as? JsonObject ?: throw IllegalArgumentException("No page rows")
        val key = Page.itemKeys.firstOrNull { obj[it] is JsonArray } ?: throw IllegalArgumentException("No page rows")
        val items = API.json.decodeFromJsonElement(list, obj[key]!!)
        val total = obj["total"].int ?: obj["count"].int
        return Page(items, total, obj["hasMore"].bool)
    }

    override fun serialize(encoder: Encoder, value: Page<T>) {
        throw UnsupportedOperationException("Pages are only read")
    }
}
