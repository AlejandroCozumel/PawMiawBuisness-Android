package com.pawmiaw.business.records

//
//  Stand-ins for the Records pieces the Inbox uses (ported in wave 2). Deleted at merge.
//  `RecordsText` itself stands in `_stubs_models_b.kt`: its two functions here are extensions.
//

// STUB: Modules/Records/RecordsText.swift
fun RecordsText.dayKey(iso: String?): String = TODO("STUB: RecordsText.swift")

// STUB: Modules/Records/RecordsText.swift
fun RecordsText.followUpTitle(title: String?): String = TODO("STUB: RecordsText.swift")

// STUB: Modules/Records/RecordsPetLookup.swift
object RecordsPetLookup {
    data class Found(
        var clientId: String? = null,
        var petName: String? = null,
    )

    suspend fun find(petId: String): Found = TODO("STUB: RecordsPetLookup.swift")
}
