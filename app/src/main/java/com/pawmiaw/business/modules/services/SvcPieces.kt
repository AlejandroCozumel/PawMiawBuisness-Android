package com.pawmiaw.business.services

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.launch

//
//  SvcPieces.kt (iOS SvcPieces.swift)
//
//  The few web pieces only the Services pages draw (the tab bar, chips, the
//  settings card, the save bar and the context pills are the kit's): the care
//  card's handling tags, the report's choice chip, the "Asked for" box, the
//  "couldn't load" line and the message preview.
//

// MARK: - Chips

/**
 * The care card's handling tags (web `HandlingChips`): what can hurt someone or
 * lose the pet first, in red with the shield; a nervous pet in mango; the rest pink.
 * `large`: the home visit's card shows them a size up.
 */
@Composable
fun SvcHandlingChips(
    tags: List<String>,
    large: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) return
    FlowLayout(spacing = 4.dp, modifier = modifier) {
        for (tag in tags.sortedBy { if (SvcHandling.warn.contains(it)) 0 else 1 }) {
            val warn = SvcHandling.warn.contains(tag)
            val ink = if (warn) Theme.destructive else Theme.inkStrong
            val fill = when {
                warn -> Theme.wash(Theme.destructive, 12.0)
                tag == "nervous" -> Theme.wash(Theme.mango, 32.0)
                else -> Theme.wash(Theme.pink, 22.0)
            }
            Row(
                Modifier
                    .background(fill, CapsuleShape)
                    .padding(horizontal = if (large) 10.dp else 8.dp, vertical = if (large) 4.dp else 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (warn) Glyph("shield-alert", 12.dp, tint = ink)
                Text(
                    SvcReportText.option(tag),
                    style = brandText(if (large) 12.5 else 10.9, FontWeight.ExtraBold),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One choice of a report or a care card (web: the `min-h-8` option buttons):
 * 32 tall, a 2 pt border; chosen = solid PAW Blue, or red for a worry ("Diarrhea", "Bites").
 * `wash`: chosen = a blue border on an aqua wash (a session's "A walk / A class").
 */
@Composable
fun SvcChoiceChip(
    title: String,
    isSelected: Boolean,
    danger: Boolean = false,
    wash: Boolean = false,
    height: Dp = 32.dp,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val ink = when {
        !isSelected -> Theme.inkSoft
        danger -> Theme.destructive
        wash -> Theme.inkStrong
        else -> Theme.onBlue
    }
    val fill = when {
        !isSelected || danger -> Color.Transparent
        wash -> Theme.wash(Theme.aqua, 18.0)
        else -> Theme.blue
    }
    val edge = if (isSelected) (if (danger) Theme.destructive else Theme.blue) else Theme.borderSoft
    Box(
        modifier
            .semantics { selected = isSelected }
            .plainButton(onClick = action)
            .heightIn(min = height)
            .background(fill, CapsuleShape)
            .border(2.dp, edge, CapsuleShape)
            .padding(horizontal = if (wash) 14.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = Theme.text(14, FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What the owner asked the groomer for, in their words (web: the aqua "Asked for" box). */
@Composable
fun SvcAskedBox(
    text: String,
    lineLimit: Int? = 3,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.aqua, 14.0), RoundedCornerShape(Theme.chipRadius))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Eyebrow(L("Asked for"), size = 9.9)
        Text(
            text,
            style = Theme.text(14),
            color = Theme.inkStrong,
            maxLines = lineLimit ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A list that could not load (web `GroomLoadError` / `VisitsLoadError`): one line
 * and a way to try again on a dashed card, never a blank page.
 */
@Composable
fun SvcLoadError(
    message: String,
    compact: Boolean = true,
    modifier: Modifier = Modifier,
    retry: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.surface, RoundedCornerShape(Theme.cardRadius))
            .dashedBorder(1.dp, Theme.borderSoft, Theme.cardRadius, 5.dp, 4.dp)
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = Theme.text(14), color = Theme.inkSoft, textAlign = TextAlign.Center)
        PMButton(
            L("Try again"),
            systemImage = "rotate-cw",
            variant = ButtonVariant.outline,
            size = if (compact) ButtonSize.compact else ButtonSize.regular,
            isWorking = retrying,
        ) {
            scope.launch {
                retrying = true
                try {
                    retry()
                } finally {
                    retrying = false
                }
            }
        }
    }
}

// MARK: - Settings

/** The message as it lands in the chat: a bubble in WhatsApp's green (web: the settings' preview). */
@Composable
fun SvcMessagePreview(
    text: String,
    standard: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(if (standard) L("Preview · standard message") else L("Preview"), size = 10.6)
        Text(
            text,
            style = Theme.text(14),
            color = Theme.inkStrong,
            modifier = Modifier
                .background(
                    Theme.wash(ProviderState.done.color, 18.0),
                    RoundedCornerShape(topStart = 17.6.dp, topEnd = 17.6.dp, bottomEnd = 17.6.dp, bottomStart = 4.8.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** The "Add:" row of a message's placeholders ({pet}, {client}…), each a small mono pill. */
@Composable
fun SvcPlaceholderRow(
    placeholders: List<String>,
    onAdd: (String) -> Unit,
    trailing: String? = null,
    extra: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
) {
    FlowLayout(spacing = 6.dp, modifier = modifier) {
        Text(L("Add:"), style = Theme.text(12), color = Theme.inkSoft)
        for (token in placeholders) {
            Box(
                Modifier
                    .plainButton { onAdd(token) }
                    .heightIn(min = 28.dp)
                    .background(Theme.surface, CapsuleShape)
                    .border(1.dp, Theme.borderSoft, CapsuleShape)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    token,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    color = Theme.inkStrong,
                )
            }
        }
        if (extra != null) {
            Box(
                Modifier
                    .plainButton(onClick = extra.second)
                    .heightIn(min = 28.dp)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(extra.first, style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
            }
        }
        if (trailing != null) Text(trailing, style = Theme.text(12), color = Theme.inkSoft)
    }
}
