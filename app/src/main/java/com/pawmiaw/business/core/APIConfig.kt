package com.pawmiaw.business

import android.net.Uri
import android.os.Build

/**
 * The backend's address (iOS APIConfig): the same backend the family app and the
 * web use. Debug builds talk to the local backend (BuildConfig.API_BASE_URL),
 * release builds to the live API.
 */
object APIConfig {
    /** Backend API base, including the `/api` suffix. A debug launch may override it. */
    val baseURL: String
        get() = DebugLaunch.apiBaseUrl ?: BuildConfig.API_BASE_URL

    /** Scheme + host (drops `/api`): resolves relative asset paths such as pet photos. */
    val origin: String get() = baseURL.removeSuffix("/").removeSuffix("/api")

    /** The web app, for what this app sends people to (the plan, a public page). */
    val webOrigin: String get() = BuildConfig.WEB_ORIGIN

    /** A path on the web app ("provider/billing" → https://pawmiaw.com/provider/billing). */
    fun webURL(path: String): Uri = Uri.parse(webOrigin.removeSuffix("/") + "/" + path.removePrefix("/"))

    /** A photo or file path from the API, absolute or relative to the origin. */
    fun assetURL(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        if (value.startsWith("http")) return value
        return origin + "/" + value.removePrefix("/")
    }

    val userAgent: String by lazy {
        "PawMiawNegocios-Android/${BuildConfig.VERSION_NAME} (${Build.MODEL}; Android ${Build.VERSION.RELEASE})"
    }
}

/**
 * Debug-only launch overrides (iOS: environment variables for a design review):
 * `adb shell am start -n com.pawmiaw.business/.MainActivity -e PAWMIAW_DEBUG_SESSION_TOKEN … -e PAWMIAW_DEBUG_SECTION schedule`.
 * Release builds ignore them.
 */
object DebugLaunch {
    var sessionToken: String? = null
    var section: String? = null
    var apiBaseUrl: String? = null

    fun read(extras: android.os.Bundle?) {
        if (!BuildConfig.DEBUG || extras == null) return
        extras.getString("PAWMIAW_DEBUG_SESSION_TOKEN")?.takeIf { it.isNotBlank() }?.let { sessionToken = it }
        extras.getString("PAWMIAW_DEBUG_SECTION")?.takeIf { it.isNotBlank() }?.let { section = it }
        extras.getString("PAWMIAW_API_BASE_URL")?.takeIf { it.isNotBlank() }?.let { apiBaseUrl = it }
    }
}
