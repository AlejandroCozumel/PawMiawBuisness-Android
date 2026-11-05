package com.pawmiaw.business

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

//
//  The plumbing of a list inside a card (iOS Lists.swift; web: the cards of Cuentas, Tienda,
//  Farmacia, Inventario, Expedientes, the agenda list): the card that runs its rows edge to
//  edge, its toolbar strip, the footer with the count, "Showing 25 of 81", the row that asks
//  for the next 20, a day's band, and rows between hairlines. One look each.
//

/**
 * The frame of a card whose rows run edge to edge (rows draw their own dividers).
 * `card`: the standard card (web `rounded-card border bg-surface shadow-card overflow-hidden`).
 * `panel`: web pharmacy-bits `ListPanel` (a 2 pt edge, no shadow).
 * `box`: a bordered box of rows inside a sheet (16 corners, 2 pt edge: the lots, a review).
 */
enum class ListCardStyle { card, panel, box }

/** A card whose rows run edge to edge: `ListCard { rows }`, or `Modifier.listCard()` on a column. */
@Composable
fun ListCard(
    style: ListCardStyle = ListCardStyle.card,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.listCard(style), content = content)
}

/** Frames a column of rows as a list card (see `ListCardStyle`). */
fun Modifier.listCard(style: ListCardStyle = ListCardStyle.card): Modifier = composed {
    val shape = RoundedCornerShape(if (style == ListCardStyle.box) Theme.boxRadius else Theme.cardRadius)
    this
        .fillMaxWidth()
        .then(if (style == ListCardStyle.card) Modifier.cardShadow(shape) else Modifier)
        .background(if (style == ListCardStyle.box) Color.Transparent else Theme.surface, shape)
        .clip(shape)
        .border(if (style == ListCardStyle.card) 1.dp else 2.dp, Theme.borderSoft, shape)
}

/** A card's toolbar strip (web: a card's toolbar row, padding 16/12, a hairline under it). */
@Composable
fun CardToolbar(
    spacing: Dp = 12.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        RowDivider(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * A card's footer line under a list (web: the pagination footer — a top hairline, "12
 * clientes" bold and tabular, " · what they add up to" grey, controls at the right).
 */
@Composable
fun ListFooter(
    count: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        RowDivider(modifier = Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(count, style = Theme.text(14, FontWeight.Bold).copy(fontFeatureSettings = "tnum"), color = Theme.inkStrong)
            // The detail takes the room the controls leave (SwiftUI `Spacer(minLength: 8)` after it).
            Box(Modifier.weight(1f).padding(end = 8.dp)) {
                if (detail != null) Text("· $detail", style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

/**
 * The count under a list (web pharmacy-bits `ListCount`): "Showing 25 of 81", or what the list
 * holds when all of it shows. `one` / `many` are the words ("1 prescription", "%d
 * prescriptions"), already in the screen's language. Counts are the server's.
 */
@Composable
fun ListCount(shown: Int, total: Int, one: String, many: String, modifier: Modifier = Modifier) {
    if (total > 0) {
        Text(
            if (shown < total) LF("Showing %d of %d", shown, total) else if (total == 1) one else LocalizationManager.format(many, arrayOf(total)),
            style = Theme.text(14),
            color = Theme.inkSoft,
            modifier = modifier.padding(horizontal = 4.dp),
        )
    }
}

/** The next 20 of a list, asked for when its last row shows (the owner's infinite scroll). */
@Composable
fun LoadMoreRow(hasMore: Boolean, busy: Boolean = false, modifier: Modifier = Modifier, load: () -> Unit) {
    if (hasMore) {
        Box(
            modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .onScrolledIntoView { if (!busy) load() },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Theme.blue, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
    }
}

/** iOS `DayBand.Style`. */
object DayBand {
    /**
     * - `agenda` (agenda-list `DayHeading`): 14 bold on the muted band, "Hoy" in blue + " · 4 de nov".
     * - `records` (record `DayHeading`): 12 extrabold navy on muted 60%.
     * - `bills` (`InvoiceDayHeader`): 12 bold navy on muted 35%, its count at the right.
     * - `caps` (the Pharmacy's lists): 12 bold uppercase, tracked, grey on the muted band.
     */
    enum class Style { agenda, records, bills, caps }
}

/** A day's band over a list's rows (each web list has its own `DayBand.Style`). */
@Composable
fun DayBand(
    title: String,
    /** `agenda`: the day's name before the date ("Hoy"), blue when `highlighted`. */
    lead: String? = null,
    highlighted: Boolean = false,
    /** Words at the right (a count). */
    trailing: String? = null,
    style: DayBand.Style = DayBand.Style.agenda,
    modifier: Modifier = Modifier,
) {
    val fill = when (style) {
        DayBand.Style.agenda, DayBand.Style.caps -> Theme.surfaceMuted
        DayBand.Style.records -> Theme.mix(Theme.surfaceMuted, 60.0, Theme.surface)
        DayBand.Style.bills -> Theme.wash(Theme.surfaceMuted, 35.0)
    }
    Box(modifier.fillMaxWidth().background(fill)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (style == DayBand.Style.agenda) 8.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The day's words take the room the count leaves (SwiftUI `Spacer(minLength: 4)` after them).
            Row(Modifier.weight(1f).alignByBaseline().padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val line = Modifier.alignByBaseline()
                when (style) {
                    DayBand.Style.agenda -> if (lead != null) {
                        Text(lead, style = Theme.text(14, FontWeight.Bold), color = if (highlighted) Theme.blue else Theme.inkStrong, maxLines = 1, modifier = line)
                        Text("· $title", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = line)
                    } else {
                        Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = line)
                    }
                    DayBand.Style.records -> Text(title, style = Theme.text(12, FontWeight.ExtraBold).copy(letterSpacing = 0.24.sp), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DayBand.Style.bills -> Text(title, style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DayBand.Style.caps -> Text(title.uppercase(), style = Theme.text(12, FontWeight.Bold).copy(letterSpacing = (12 * 0.12).sp), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Text(
                    trailing,
                    style = Theme.text(12, if (style == DayBand.Style.agenda) FontWeight.SemiBold else FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
                    color = Theme.inkSoft,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (style != DayBand.Style.bills) RowDivider(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth())
    }
}

/** Rows with a hairline between them (web `divide-y`). */
@Composable
fun <Item> RowList(
    items: List<Item>,
    modifier: Modifier = Modifier,
    row: @Composable (Item) -> Unit,
) {
    Column(modifier) {
        items.forEachIndexed { index, item ->
            if (index > 0) RowDivider(modifier = Modifier.fillMaxWidth())
            row(item)
        }
    }
}
