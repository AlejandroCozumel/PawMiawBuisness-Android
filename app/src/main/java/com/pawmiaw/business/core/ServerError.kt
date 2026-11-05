package com.pawmiaw.business

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How the app reads what the backend says when a request fails (iOS ServerError.swift):
 * `{ error, code, fields }`, a schema refusal `{ success: false, error: { message | issues } }`,
 * or `{ message }`.
 */
class ServerErrorBody(val error: String, val code: String?, val fields: Map<String, String>) {
    companion object {
        fun parse(raw: JsonElement?): ServerErrorBody? {
            val obj = raw as? JsonObject ?: return null
            val code = obj["code"].string
            val fields = (obj["fields"] as? JsonObject)?.mapNotNull { (k, v) -> v.string?.let { k to it } }?.toMap() ?: emptyMap()
            var text: String? = null
            when (val error = obj["error"]) {
                is JsonPrimitive -> text = error.string
                is JsonObject -> {
                    val issues = error["issues"] as? JsonArray
                    text = issues?.firstNotNullOfOrNull { it["message"].string }
                        ?: error["message"].string?.let { raw -> firstIssueMessage(raw) ?: raw }
                }
                else -> Unit
            }
            if (text == null) text = obj["message"].string
            if (text.isNullOrEmpty()) return null
            val message = if (text == "Unauthorized" || text == "No autorizado.") L("Your session expired. Please sign in again.") else text
            return ServerErrorBody(message, code, fields)
        }

        private fun firstIssueMessage(raw: String): String? =
            runCatching { (API.json.parseToJsonElement(raw) as? JsonArray)?.firstNotNullOfOrNull { it["message"].string } }.getOrNull()
    }
}
