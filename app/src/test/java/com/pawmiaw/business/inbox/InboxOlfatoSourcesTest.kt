package com.pawmiaw.business.inbox

import com.pawmiaw.business.API
import com.pawmiaw.business.ProviderRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sources under an Olfato AI answer ("Fuentes", paw-miaw-backend provider-olfato.schemas.ts
 * `SourceSchema`): read entry by entry, a bad one passed over and never the whole message, each
 * shown once, and their links opened through the same mapping as "Ver en …".
 */
class InboxOlfatoSourcesTest {
    private fun page(sources: String?): InboxOlfatoMessagesPage {
        val field = if (sources == null) "" else """, "sources": $sources"""
        val body = """{ "messages": [ { "id": "m1", "role": "assistant", "content": "Hola", "status": "done"$field } ], "hasOlder": false }"""
        return API.json.decodeFromString(InboxOlfatoMessagesPage.serializer(), body)
    }

    private fun reply(sources: String?): InboxOlfatoMessage = InboxOlfatoMessage(page(sources).messages!!.single())

    @Test
    fun missingNullOrOddSourcesAreNone() {
        assertTrue(reply(null).sources.isEmpty())
        assertTrue(reply("null").sources.isEmpty())
        assertTrue(reply("{}").sources.isEmpty())
        assertTrue(reply("\"x\"").sources.isEmpty())
        assertTrue(reply("[]").sources.isEmpty())
    }

    @Test
    fun badEntriesAreSkippedNotTheMessage() {
        val sources = reply(
            """[
              { "kind": "prescription", "id": "rx1", "petId": "p1", "title": "Receta: Benazepril, Amlodipino", "date": "2025-03-14",
                "link": { "to": "/provider/records/${'$'}recordId", "params": { "recordId": "rec1" }, "label": { "es": "Ver receta", "en": "See prescription" } } },
              { "kind": "visit", "id": "v1", "petId": "p1" },
              { "id": "x", "title": "No kind" },
              "not an object",
              { "kind": "document", "id": "d1", "petId": "p1", "title": "panel_renal_toby.pdf", "page": 2, "date": "14/03/2025", "link": "nope" },
              { "kind": "document", "id": "d1", "petId": "p1", "title": "panel_renal_toby.pdf", "page": 2 },
              { "kind": "document", "id": "d1", "petId": "p1", "title": "panel_renal_toby.pdf", "page": 3 },
              { "kind": "weight", "id": "w1", "petId": "p1", "title": "Peso", "page": "2", "extra": true },
              { "kind": "something_new", "id": "n1", "title": "Algo nuevo" }
            ]""",
        ).sources
        assertEquals(listOf("prescription|rx1|", "document|d1|2", "document|d1|3", "weight|w1|", "something_new|n1|"), sources.map { it.key })
        val rx = sources[0]
        assertEquals("Receta: Benazepril, Amlodipino", rx.title)
        assertEquals("2025-03-14", rx.date)
        assertEquals("pill", rx.icon)
        assertEquals(ProviderRoute.record("rec1"), rx.route)
        val doc = sources[1]
        assertNull(doc.date)
        assertNull(doc.link)
        assertNull(doc.route)
        assertEquals(2, doc.page)
        assertNull(sources[3].page)
        assertEquals("", sources[4].petId)
        assertEquals("file-text", sources[4].icon)
    }

    @Test
    fun userQuestionsCarryNoSources() {
        val body = """{ "messages": [ { "id": "q1", "role": "user", "content": "¿Qué toma?", "sources": [ { "kind": "patient", "id": "p1", "title": "Toby" } ] } ] }"""
        val row = API.json.decodeFromString(InboxOlfatoMessagesPage.serializer(), body).messages!!.single()
        assertEquals(1, row.sources.size)
        assertTrue(InboxOlfatoMessage(row).sources.isEmpty())
    }

    @Test
    fun linksOpenTheirScreens() {
        fun route(link: String) = InboxOlfatoSource.from(
            API.json.parseToJsonElement("""{ "kind": "visit", "id": "a", "title": "T", "link": $link }"""),
        )?.route
        assertEquals(ProviderRoute.record("r1"), route("""{ "to": "/provider/records/${'$'}recordId", "params": { "recordId": "r1" } }"""))
        assertEquals(ProviderRoute.appointment("a1"), route("""{ "to": "/provider/appointments/${'$'}appointmentId", "params": { "appointmentId": "a1" } }"""))
        assertEquals(ProviderRoute.hospitalization("h1"), route("""{ "to": "/provider/hospital/${'$'}hospitalizationId", "params": { "hospitalizationId": "h1" } }"""))
        assertEquals(ProviderRoute.stay("s1"), route("""{ "to": "/provider/stays", "search": { "stay": "s1" } }"""))
        assertEquals(
            ProviderRoute.clientPet(clientId = "c1", petId = "p1"),
            route("""{ "to": "/provider/clients/${'$'}clientId/pets/${'$'}petId", "params": { "clientId": "c1", "petId": "p1" }, "search": { "tab": "weights" } }"""),
        )
        // A parameter left unfilled, or a screen outside the workspace, opens nothing.
        assertNull(route("""{ "to": "/provider/records/${'$'}recordId" }"""))
        assertNull(route("""{ "to": "/app/pets/p1" }"""))
    }
}
