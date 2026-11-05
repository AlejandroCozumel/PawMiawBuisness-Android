package com.pawmiaw.business

import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

//
//  Spanish first, like the business side of the web (iOS Localization.swift). Keys are the
//  English sentence; each language's words live in `assets/i18n/<lang>/<Table>.json`, one
//  table per module (Localizable for the shared pieces, Schedule, Clients, …), copied from
//  the iOS tables by scripts/strings-from-ios.py.
//
//  A module's files read their own table first: every module package has its own
//  `L`/`LF`/`LP` (modules/<module>/Strings.kt) calling `localized(key, "<Module>")`, and Kotlin
//  prefers a same-package function over the root one. So "Cancelled" can be "Cancelada" for
//  a visit and "Cancelado" for a groom. Then the shared table, then any other. A key may carry
//  a context after "@" ("Done@home"); if no table has it, the English before the "@" shows.
//

enum class AppLanguage(val rawValue: String) {
    spanish("es"),
    english("en");

    val displayName: String
        get() = when (this) {
            english -> "English"
            spanish -> "Español"
        }

    val localeIdentifier: String
        get() = when (this) {
            english -> "en_US"
            spanish -> "es_MX"
        }

    val locale: Locale
        get() = when (this) {
            english -> Locale.US
            spanish -> Locale("es", "MX")
        }

    companion object {
        fun from(raw: String?): AppLanguage? = entries.firstOrNull { it.rawValue == raw }
    }
}

object LocalizationManager {
    private const val LANGUAGE_KEY = "pawmiaw_negocios_language"

    /** The app's language; reading it in a composable recomposes on a change. */
    var currentLanguage by mutableStateOf(AppLanguage.spanish)
        private set

    /** The language's tables by name ("Schedule", "Localizable"). */
    private var tables: Map<String, Map<String, String>> = emptyMap()
    /** Every table but the shared one, merged: the last resort for a key. */
    private var merged: Map<String, String> = emptyMap()

    val locale: Locale get() = currentLanguage.locale

    fun init() {
        val stored = AppLanguage.from(AppContext.defaults.getString(LANGUAGE_KEY, null))
        currentLanguage = stored ?: run {
            // Spanish unless the device asks for English first.
            val device = Resources.getSystem().configuration.locales[0]?.language
            if (device == "en") AppLanguage.english else AppLanguage.spanish
        }
        loadTables()
    }

    fun setLanguage(language: AppLanguage) {
        AppContext.defaults.edit().putString(LANGUAGE_KEY, language.rawValue).apply()
        if (language == currentLanguage) return
        loadTables(language)
        currentLanguage = language
    }

    /** The account's language from the server, unless the person chose one here. */
    fun syncFromServerPreference(value: String?) {
        if (AppContext.defaults.getString(LANGUAGE_KEY, null) != null) return
        val language = AppLanguage.from(value) ?: return
        if (language == currentLanguage) return
        loadTables(language)
        currentLanguage = language
    }

    private fun loadTables(language: AppLanguage = currentLanguage) {
        val assets = AppContext.context.assets
        val folder = "i18n/${language.rawValue}"
        val loaded = mutableMapOf<String, Map<String, String>>()
        val all = mutableMapOf<String, String>()
        for (file in (assets.list(folder) ?: emptyArray()).sorted()) {
            if (!file.endsWith(".json")) continue
            val name = file.removeSuffix(".json")
            val text = assets.open("$folder/$file").bufferedReader().use { it.readText() }
            val obj = runCatching { API.json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: continue
            val table = obj.mapValues { it.value.jsonPrimitive.content }
            loaded[name] = table
            if (name != "Localizable") for ((k, v) in table) all.putIfAbsent(k, v)
        }
        tables = loaded
        merged = all
    }

    fun localizedString(key: String, table: String?): String {
        if (table != null) tables[table]?.get(key)?.let { return it }
        tables["Localizable"]?.get(key)?.let { return it }
        merged[key]?.let { return it }
        return withoutContext(key)
    }

    /** "Done@home" → "Done" (a key's context is never shown). */
    private fun withoutContext(key: String): String {
        val at = key.lastIndexOf('@')
        if (at <= 0) return key
        val context = key.substring(at + 1)
        if (context.isEmpty() || !context.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return key
        return key.substring(0, at)
    }

    /** Swift `String(format:)` placeholders (`%@`, `%ld`, `%1$@`) as Java's. */
    fun javaFormat(format: String): String =
        format.replace(Regex("%(\\d+\\$)?@"), "%$1s").replace(Regex("%(\\d+\\$)?l+([dui])"), "%$1d").replace(Regex("%(\\d+\\$)?u"), "%$1d")

    fun format(format: String, args: Array<out Any?>): String =
        runCatching { String.format(locale, javaFormat(format), *args) }.getOrElse { format }
}

/** A sentence from a module's table first ("Schedule"), then the shared one. */
fun localized(key: String, table: String?): String = LocalizationManager.localizedString(key, table)

/** The sentence in the app's language (the shared table). The key is the English sentence. */
fun L(key: String): String = LocalizationManager.localizedString(key, null)

/** A sentence with `%@` / `%d` arguments, in the app's language. */
fun LF(key: String, vararg arguments: Any?): String = LocalizationManager.format(L(key), arguments)

/** "1 visit" / "3 visits": the key's `one` and `other` forms, each with `%d`. */
fun LP(count: Int, one: String, other: String): String = LocalizationManager.format(L(if (count == 1) one else other), arrayOf(count))

/** The locale of the language chosen in the app (en_US / es_MX), for anything formatted for display. */
val appLocale: Locale get() = LocalizationManager.locale
