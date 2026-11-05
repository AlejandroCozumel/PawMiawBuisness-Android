package com.pawmiaw.business

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  The Olfato AI notice's six points (iOS OlfatoNotice.swift; web components/app/olfato/
//  olfato-consent.tsx; backend docs/patient-summaries.md and docs/olfato-business-chat-plan.md):
//  what it does, what is sent, who receives it, checking it against the record, what is kept,
//  and telling your clients. The same words wherever someone reads it before confirming:
//  Team's Settings › Olfato AI and Inbox's chat gate.
//

/** iOS `OlfatoNotice.points`: (icon, title, text), the English keys of the words. */
object OlfatoNotice {
    val points: List<Triple<String, String, String>> = listOf(
        Triple("bubble.left.and.text.bubble.right", "What it does", "Olfato AI writes a brief of each pet’s record, and now has a chat too, where your team asks about a pet or about the business. It reads and never acts: it doesn’t book, charge, send or change anything. Each person only gets answers from what they can already see in PawMiaw."),
        Triple("doc.text", "What is sent", "The question and the chat so far; the business’s name and locations, and the name and role of the person asking; the pet’s record under the brief’s rules (clinical records, including the team’s internal notes for those who can see clinical records, visit notes and reports, prescriptions, weights, the care card and the text of attached documents), plus the owner’s name and, for those who see bills, what is owed; and what the answer needs from the business: client, pet and staff names, visits, bills and payments with who billed or collected them, stock and the controlled register (prescriber, lot, folio, who moved it), and what people wrote in incidents, hospital stays, follow-ups, families’ requests to move or cancel, and groom requests. Only what the person asking is allowed to see. Email, phone and address fields and card data are never sent; emails and phone numbers found in notes are removed, but something typed in a note, such as an address, can still be sent."),
        Triple("building.2", "Who receives it", "An AI company outside PawMiaw receives that text to write the brief and the answers. That is not a promise that nothing is kept: it may retain data for a limited time to watch for abuse and security problems."),
        Triple("checkmark.shield", "Check it against the record", "The brief and the chat can leave something out or get it wrong. They say what they read. The record is what counts: check it before acting."),
        Triple("archivebox", "What is kept", "Chats are private to the person who asked: nobody else in the business reads them, owners included. Each one is deleted 90 days after its last message, and all of a person’s chats are deleted when they leave the business or are suspended. Owners, admins and whoever manages roles or money settings see a log of who asked about which pet, never the text; each person sees their own, and a pet’s history shows who asked about it. The log is kept 1 year."),
        Triple("person.crop.circle.badge.checkmark", "Your clients", "Veterinary regulators ask practices to be open with clients about using AI on their records. Tell your clients that your business uses Olfato AI, as your own policies and local rules require."),
    )
}

/** The six points as cards in a grid: as many columns as fit at `minimumWidth` each, 10 apart. */
@Composable
fun OlfatoNotice(
    /** The narrowest a point gets before the grid wraps. */
    minimumWidth: Dp = 280.dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 10.dp
        val columns = maxOf(1, ((maxWidth + gap) / (minimumWidth + gap)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            OlfatoNotice.points.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                    row.forEach { (icon, title, text) -> OlfatoPoint(icon, title, text, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun OlfatoPoint(icon: String, title: String, text: String, modifier: Modifier) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(Theme.aqua.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(icon, 15.dp, tint = Theme.inkOnBright)
            }
            Text(L(title), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        }
        Text(L(text), style = Theme.text(13), color = Theme.inkSoft)
    }
}
