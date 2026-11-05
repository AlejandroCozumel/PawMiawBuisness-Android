package com.pawmiaw.business

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

//
//  Who is signed in (iOS Session.swift). The backend's Bearer token (Better Auth's signed
//  session cookie value, captured at sign-in and kept percent-encoded: the bearer plugin
//  decodes it itself) is stored privately and sent on every request.
//

@Serializable
data class AuthUser(
    val id: String,
    val email: String,
    val name: String,
    val emailVerified: Boolean? = null,
    val image: String? = null,
    val preferredLanguage: String? = null,
)

@Serializable private data class SignInBody(val user: AuthUser)
@Serializable private data class ValidateBody(val valid: Boolean = false, val user: AuthUser? = null)

/** The token, readable from anywhere (the API client signs requests with it). */
object SessionToken {
    private const val KEY = "pawmiaw_negocios_session"
    private var cached: String? = null
    private var loaded = false

    val current: String?
        get() {
            // A local design review signs in with a session made for it, without touching the stored one.
            DebugLaunch.sessionToken?.let { return it }
            if (!loaded) { cached = AppContext.defaults.getString(KEY, null); loaded = true }
            return cached
        }

    fun store(token: String?) {
        cached = token; loaded = true
        AppContext.defaults.edit().apply { if (token == null) remove(KEY) else putString(KEY, token) }.apply()
    }
}

class SessionStore private constructor() {
    sealed class Phase {
        data object checking : Phase()
        data object signedOut : Phase()
        data class signedIn(val user: AuthUser) : Phase()
    }

    var phase: Phase by mutableStateOf(Phase.checking)
        private set
    val user: AuthUser? get() = (phase as? Phase.signedIn)?.user

    private val userKey = "pawmiaw_negocios_user"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        val shared by lazy { SessionStore() }
    }

    init {
        val stored = AppContext.defaults.getString(userKey, null)?.let { runCatching { API.json.decodeFromString(AuthUser.serializer(), it) }.getOrNull() }
        phase = when {
            SessionToken.current != null && stored != null -> Phase.signedIn(stored)
            SessionToken.current == null -> Phase.signedOut
            else -> Phase.checking
        }
        scope.launch { AppEvents.sessionMayHaveExpired.collectLatest { revalidate() } }
    }

    suspend fun signIn(email: String, password: String) {
        val request = API.request("POST", "/auth/sign-in", body = API.jsonBody(mapOf("email" to email, "password" to password, "rememberMe" to true)))
        val response = API.execute(request)
        val bytes = response.body?.bytes() ?: ByteArray(0)
        val status = response.code
        // The session comes back as Better Auth's signed session cookie; its value (kept as
        // sent, percent-encoded) is what the bearer plugin accepts in `Authorization`.
        val token = response.headers("Set-Cookie").firstNotNullOfOrNull { header ->
            val pair = header.substringBefore(";")
            val name = pair.substringBefore("=")
            if (name.endsWith("better-auth.session_token")) pair.substringAfter("=") else null
        }
        response.close()
        if (status !in 200..299) throw API.errorFrom(bytes, status)
        if (token.isNullOrEmpty()) throw APIError(500, L("The sign-in did not return a session. Try again."), null, emptyMap())
        val body: SignInBody = API.decode(bytes)
        SessionToken.store(token)
        remember(body.user)
        LocalizationManager.syncFromServerPreference(body.user.preferredLanguage)
        phase = Phase.signedIn(body.user)
    }

    suspend fun requestPasswordReset(email: String) {
        API.send("POST", "/auth/forget-password", mapOf("email" to email, "redirectTo" to APIConfig.webURL("reset-password").toString()))
    }

    /** Checks the stored session once with the server; signs out when it ended. */
    suspend fun revalidate() {
        if (SessionToken.current == null) { phase = Phase.signedOut; return }
        try {
            val body: ValidateBody = API.get("/auth/validate-session")
            val user = body.user
            if (body.valid && user != null) {
                remember(user)
                phase = Phase.signedIn(user)
            } else {
                clear()
            }
        } catch (e: APIError) {
            if (e.status == 401) clear()
            // Offline: keep the person signed in with what we know.
            else if (phase == Phase.checking) phase = user?.let { Phase.signedIn(it) } ?: Phase.signedOut
        }
    }

    suspend fun signOut() {
        runCatching { API.send("POST", "/auth/sign-out", emptyMap()) }
        clear()
    }

    private fun remember(user: AuthUser) {
        AppContext.defaults.edit().putString(userKey, API.json.encodeToString(AuthUser.serializer(), user)).apply()
    }

    private fun clear() {
        SessionToken.store(null)
        AppContext.defaults.edit().remove(userKey).apply()
        phase = Phase.signedOut
    }
}
