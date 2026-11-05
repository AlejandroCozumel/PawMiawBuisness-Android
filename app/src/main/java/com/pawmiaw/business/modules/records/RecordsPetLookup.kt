package com.pawmiaw.business.records

import com.pawmiaw.business.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

//
//  RecordsPetLookup.kt (iOS RecordsPetLookup.swift)
//
//  Whose a pet is and what it is called, from its provider client pet id
//  alone. The backend has no route that reads one pet, so the pet's own
//  lists say it: its records, visits and follow-ups carry its client and its
//  name, its chart summary its client, and the client its pets. Only what the
//  reader's role can read answers; the rest is skipped. (A backend route
//  returning one pet with its client would replace all of this.)
//

object RecordsPetLookup {
    data class Found(
        var clientId: String? = null,
        var petName: String? = null,
    )

    suspend fun find(petId: String): Found {
        val found = Found()
        val probes: List<Pair<String, Map<String, String?>>> = listOf(
            "/provider-records" to mapOf("limit" to "1", "offset" to "0", "providerClientPetId" to petId),
            "/provider-appointments" to mapOf("limit" to "1", "offset" to "0", "providerClientPetId" to petId),
            "/provider-follow-ups" to mapOf("limit" to "1", "offset" to "0", "status" to "all", "providerClientPetId" to petId),
            "/provider-client-pets/$petId/chart-summary" to emptyMap(),
        )
        for ((path, query) in probes) {
            val value: JSONValue = tryOrNull { API.get<JSONValue>(path, query) } ?: continue
            scan(value, petId, found)
            if (found.clientId != null) break
        }
        if (found.clientId == null) {
            // Last: the patients list, a page at a time (the pet may be new, with nothing written yet).
            outer@ for (archived in listOf(false, true)) {
                var offset = 0
                while (offset < 500) {
                    val page: JSONValue = tryOrNull {
                        API.get<JSONValue>("/provider-patients", mapOf("limit" to "50", "offset" to "$offset", "archived" to if (archived) "true" else null))
                    } ?: break
                    val items = page["items"].array ?: emptyList()
                    val row = items.firstOrNull { it["id"].string == petId }
                    if (row != null) {
                        found.clientId = row["client"]["id"].string ?: row["providerClientId"].string
                        if (found.petName == null) found.petName = row["petName"].string.nonEmpty
                        break@outer
                    }
                    if (items.size < 50) break
                    offset += 50
                }
            }
        }
        val clientId = found.clientId
        if (clientId != null && found.petName == null) {
            val client = tryOrNull { RecordsAPI.client(clientId) }
            if (client != null) found.petName = client.pets?.firstOrNull { it.id == petId }?.petName.nonEmpty
        }
        return found
    }

    /** The first object naming this pet gives its client and, when it has it, its name. */
    private fun scan(value: JSONValue, petId: String, found: Found) {
        when (value) {
            is JsonObject -> {
                if (value["providerClientPetId"].string == petId) {
                    if (found.clientId == null) found.clientId = value["providerClientId"].string.nonEmpty
                    if (found.petName == null) found.petName = value["petName"].string.nonEmpty
                }
                for (child in value.values) {
                    if (found.clientId != null && found.petName != null) break
                    scan(child, petId, found)
                }
            }
            is JsonArray -> {
                for (child in value) {
                    if (found.clientId != null && found.petName != null) break
                    scan(child, petId, found)
                }
            }
            else -> Unit
        }
    }

    /** Swift `try?`: a failure reads as null (a cancellation still cancels). */
    private suspend fun <T> tryOrNull(work: suspend () -> T): T? = try {
        work()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
