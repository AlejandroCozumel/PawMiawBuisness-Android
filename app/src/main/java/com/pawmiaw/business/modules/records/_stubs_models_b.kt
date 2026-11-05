package com.pawmiaw.business.records

// Stand-ins for what RecordsModels reads from other Records files (ported in wave 2). Deleted at merge.

// STUB: Modules/Records/RecordsKinds.swift
enum class RecordsKind(val rawValue: String) {
    vetVisit("vet_visit"), vaccination("vaccination"), deworming("deworming"), diagnosis("diagnosis"),
    medication("medication"), allergy("allergy"), labResult("lab_result"), weightCheck("weight_check"),
    surgery("surgery"), note("note");

    val label: String get() = TODO("STUB: RecordsKinds.swift")

    companion object {
        fun of(raw: String?): RecordsKind = TODO("STUB: RecordsKinds.swift")
    }
}

// STUB: Modules/Records/RecordsText.swift
object RecordsText {
    fun daysUntil(iso: String?): Int = TODO("STUB: RecordsText.swift")
}
