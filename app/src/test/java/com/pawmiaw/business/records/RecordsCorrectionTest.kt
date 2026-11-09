package com.pawmiaw.business.records

import com.pawmiaw.business.API
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signed corrections (paw-miaw-backend cce7bbd): a save of a signed record carries
 * `"signCorrection": true`, a draft's never does, and the version chain decodes as the server
 * sends it.
 */
class RecordsCorrectionTest {
    private fun record(status: String, revision: Int? = 3, signedVersion: Int? = null) = RecordsRecord(
        id = "rec_1", providerClientId = "cl_1", providerClientPetId = "pet_1", status = status,
        revision = revision, signedVersion = signedVersion,
    )

    private fun sent(body: Map<String, Any?>): JsonObject =
        API.json.parseToJsonElement(API.jsonBody(body).toString(Charsets.UTF_8)) as JsonObject

    @Test
    fun signedRecordSaveSignsTheCorrection() {
        val signed = record(status = "active", signedVersion = 1)
        assertTrue(RecordsCorrection.signs(signed))
        val body = sent(RecordsCorrection.patchBody(signed, mapOf("title" to "Nexgard", "payload" to mapOf("dose_amount" to 0.5))))
        assertEquals("true", body["signCorrection"]?.jsonPrimitive?.content)
        assertEquals("3", body["expectedRevision"]?.jsonPrimitive?.content)
        assertEquals("Nexgard", body["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun draftSaveDoesNotSign() {
        val draft = record(status = "draft")
        assertFalse(RecordsCorrection.signs(draft))
        assertFalse(RecordsCorrection.signs(null))
        val body = sent(RecordsCorrection.patchBody(draft, mapOf("summary" to "Better")))
        assertFalse(body.containsKey("signCorrection"))
        assertEquals("3", body["expectedRevision"]?.jsonPrimitive?.content)
    }

    @Test
    fun signedRecordNeverGoesBackToDraft() {
        val body = RecordsCorrection.patchBody(record(status = "active"), mapOf("status" to "draft", "details" to "x"))
        assertFalse(body.containsKey("status"))
        assertEquals(true, body["signCorrection"])
    }

    @Test
    fun visitNoteBodyCarriesTheFlagOnlyWhenSigned() {
        val fields = mapOf("details" to "Exam", "summary" to null, "internalNote" to null)
        val signed = sent(RecordsCorrection.patchBody(fields, revision = 7, signed = true))
        assertEquals("true", signed["signCorrection"]?.jsonPrimitive?.content)
        assertEquals("7", signed["expectedRevision"]?.jsonPrimitive?.content)
        val draft = sent(RecordsCorrection.patchBody(fields, revision = 7, signed = false))
        assertFalse(draft.containsKey("signCorrection"))
    }

    @Test
    fun recordDecodesItsSignedVersion() {
        val json = """{"id":"rec_1","providerClientId":"cl_1","providerClientPetId":"pet_1","status":"active","signedVersion":2,"revision":4}"""
        val decoded = API.json.decodeFromString(RecordsRecord.serializer(), json)
        assertEquals(2, decoded.signedVersion)
        val draft = API.json.decodeFromString(RecordsRecord.serializer(), """{"id":"r","providerClientId":"c","providerClientPetId":"p","status":"draft","signedVersion":null}""")
        assertNull(draft.signedVersion)
    }

    @Test
    fun versionsResponseDecodes() {
        val json = """
            {"versions":[
              {"version":1,"kept":false,"signedAt":"2026-10-06T15:00:00.000Z","signerName":"Dra. Loribeth","status":"valid",
               "verifyCode":"ABCD-1234","verifyUrl":"https://pawmiaw.com/verify/ABCD-1234","requestId":"sig_1",
               "isThis":false,"current":false,"correctedUnsigned":true,"changes":[],"important":false,"familyVisible":false},
              {"version":2,"kept":true,"signedAt":"2026-11-09T16:00:00.000Z","signerName":"Dra. Loribeth","status":"valid",
               "verifyCode":"EFGH-5678","verifyUrl":"https://pawmiaw.com/verify/EFGH-5678","requestId":"sig_2",
               "isThis":true,"current":true,"correctedUnsigned":false,
               "changes":[{"field":"payload.dose","label":"Dosis","before":"1 tableta","after":"½ tableta","important":true}],
               "important":true,"familyVisible":true}
            ]}
        """.trimIndent()
        val versions = API.json.decodeFromString(RecordsVersionList.serializer(), json).versions
        assertEquals(2, versions.size)
        val first = versions[0]
        assertEquals(1, first.version)
        assertFalse(first.kept)
        assertTrue(first.correctedUnsigned)
        assertEquals("https://pawmiaw.com/verify/ABCD-1234", first.verifyUrl)
        assertTrue(first.changes.isEmpty())
        val second = versions[1]
        assertTrue(second.current)
        assertTrue(second.kept)
        assertEquals("valid", second.status)
        assertEquals("Dosis: 1 tableta → ½ tableta", second.changes.single().line)
        assertTrue(second.changes.single().important)
    }

    @Test
    fun versionDocumentDecodes() {
        val json = """
            {"version":1,"recordType":"medication","title":"Nexkar antipulgas","summary":"Una tableta al mes","details":null,
             "payload":{"dose_amount":1,"dose_unit":"tableta"},"eventAt":"2026-10-06T18:00:00.000Z","followUpAt":null,
             "signedAt":"2026-10-06T18:05:00.000Z","signedByName":"Dra. Loribeth","current":false,
             "verifyUrl":"https://pawmiaw.com/verify/ABCD-1234"}
        """.trimIndent()
        val document = API.json.decodeFromString(RecordsVersionDocument.serializer(), json)
        assertEquals(1, document.version)
        assertFalse(document.current)
        assertEquals("tableta", document.payload?.get("dose_unit")?.jsonPrimitive?.content)
        val asSigned = document.asRecord(record(status = "active", signedVersion = 2).copy(internalNote = "staff only"))
        assertEquals("Nexkar antipulgas", asSigned.title)
        assertNull(asSigned.internalNote)
        assertEquals(1, asSigned.signedVersion)
    }

    @Test
    fun changeLineShowsADashForAnEmptySide() {
        assertEquals("Próxima fecha: — → 9 dic 2026", RecordsVersionChange(field = "followUpAt", label = "Próxima fecha", before = "", after = "9 dic 2026").line)
    }
}
