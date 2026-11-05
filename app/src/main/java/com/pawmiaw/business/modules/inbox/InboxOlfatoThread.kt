package com.pawmiaw.business.inbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max

//
//  InboxOlfatoThread.kt (iOS InboxOlfatoThread.swift)
//
//  One Olfato AI chat (web olfato-business-thread.tsx and
//  olfato-business-reply.tsx): a chat not begun opens on "Lo que puedo ver
//  contigo" and a few questions from the areas open to this person; then
//  the questions and replies (the tools each ran, its lock chips and its
//  "Ver en …" links); the box at the bottom, with stop while a reply is
//  written. When the person can't ask now, the box gives way to why.
//

/** A pet a chat is about (its provider client pet id and name). */
data class InboxOlfatoPetRef(
    val id: String,
    val name: String,
)

/** SwiftUI `.onChange(of: value) { old, new in … }`: runs on each change after the first composition. */
@Composable
private fun <T> InboxOnChange(value: T, action: (old: T, new: T) -> Unit) {
    val latest by rememberUpdatedState(action)
    val previous = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        @Suppress("UNCHECKED_CAST")
        val old = previous[0] as T
        if (old != value) {
            previous[0] = value
            latest(old, value)
        }
    }
}

/** A ring `width` wide just outside the box (iOS `.inset(by: -3).strokeBorder(color, lineWidth: 3)`). */
private fun Modifier.inboxFocusRing(color: Color, radius: Dp, width: Dp = 3.dp): Modifier = drawBehind {
    val w = width.toPx()
    val r = radius.toPx() + w / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(-w / 2, -w / 2),
        size = Size(size.width + w, size.height + w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(w),
    )
}

@Composable
fun InboxOlfatoThread(
    model: InboxOlfatoChatModel,
    viewer: InboxOlfatoViewer,
    canEditWorkspace: Boolean,
    /** A chat about this pet (from its chart): its own welcome, questions and box. */
    pet: InboxOlfatoPetRef? = null,
    /** From a chart: "Lo que puedo ver contigo" folds into one line. */
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val regular = LocalSizeClass.current == SizeClass.regular
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    /** The view follows a reply to its end, unless the reader scrolled away from it. */
    var pinned by remember { mutableStateOf(true) }
    /** Far enough from the end to offer "Ir al último mensaje". */
    var below by remember { mutableStateOf(false) }
    /** The reader's finger is on the list (a reply growing never unpins it; only the reader's scroll does). */
    var readerScrolling by remember { mutableStateOf(false) }
    var gap by remember { mutableFloatStateOf(0f) }
    val scroll = rememberScrollState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()

    val gate = viewer.gate
    val ready = gate == InboxOlfatoGate.ready
    val maxChars = viewer.panel?.chat?.maxMessageChars ?: 2000
    val draftCount = draft.codePointCount(0, draft.length)
    val trimmedDraft = draft.trim()
    val tooLong = trimmedDraft.codePointCount(0, trimmedDraft.length) > maxChars

    // MARK: Following the end

    var followTick by remember { mutableIntStateOf(0) }
    var followAnimated by remember { mutableStateOf(true) }
    fun follow(animated: Boolean = true) {
        if (!pinned) return
        followAnimated = animated
        followTick += 1
    }
    LaunchedEffect(followTick) {
        if (followTick == 0) return@LaunchedEffect
        // After the new rows are laid out (iOS `proxy.scrollTo("olfato-bottom", anchor: .bottom)`).
        withFrameNanos { }
        if (followAnimated) scroll.animateScrollTo(scroll.maxValue, tween(200, easing = EaseOut)) else scroll.scrollTo(scroll.maxValue)
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue - scroll.value }.collect { px ->
            val distance = with(density) { px.toDp().value }
            gap = distance
            below = distance > 96
            if (readerScrolling) pinned = distance < 48
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { dragged to scroll.isScrollInProgress }.collect { (drag, moving) ->
            val reader = drag || (readerScrolling && moving)
            if (readerScrolling && !reader) pinned = gap < 48
            readerScrolling = reader
        }
    }
    LaunchedEffect(Unit) { follow(animated = false) }
    InboxOnChange(model.messages.size) { _, _ -> follow() }
    InboxOnChange(model.messages.lastOrNull()?.content) { _, _ -> follow(animated = false) }
    InboxOnChange(model.messages.lastOrNull()?.status) { _, _ -> follow() }
    InboxOnChange(model.chatId) { old, _ ->
        // Another chat is read from its end, and the box starts over; a chat that just got its id
        // is the one being asked, and what is typed stays.
        if (old != null) {
            draft = ""
            problem = null
        }
        pinned = true
        follow(animated = false)
    }

    // MARK: Asking

    fun refuse(sentence: String) {
        problem = sentence
        Toast.error(sentence)
        runCatching { focusRequester.requestFocus() }
    }

    fun ask(question: String) {
        if (!ready || model.busy) return
        val text = question.trim()
        if (text.isEmpty()) return refuse(InboxOlfatoRefusal.sentence("olfato_empty"))
        if (text.codePointCount(0, text.length) > maxChars) {
            return refuse(InboxOlfatoRefusal.sentence("olfato_too_long", mapOf("maxChars" to JsonPrimitive(maxChars.toDouble()))))
        }
        pinned = true
        if (model.ask(text)) {
            draft = ""
            problem = null
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(model.formRefusal) {
        val refusal = model.formRefusal ?: return@LaunchedEffect
        // A question refused for what was typed comes back into the box, which says why.
        draft = refusal.question
        problem = refusal.sentence
        Toast.error(refusal.sentence)
        runCatching { focusRequester.requestFocus() }
        model.formRefusal = null
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Theme.canvas)) {
            Column(
                Modifier.fillMaxSize().scrollDismissesKeyboard().verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (!model.loaded) {
                        LoadingState(text = L("Loading the chat"))
                    } else if (model.messages.isEmpty()) {
                        InboxOlfatoFresh(model = model, viewer = viewer, pet = pet, compact = compact, ready = ready, regular = regular) { ask(it) }
                    } else {
                        if (model.hasOlder) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                PMButton(
                                    L("Show earlier messages"),
                                    systemImage = "chevron-up",
                                    variant = ButtonVariant.outline,
                                    size = ButtonSize.compact,
                                    isWorking = model.loadingOlder,
                                ) {
                                    scope.launch { model.loadOlder() }
                                }
                            }
                        }
                        val messages = model.messages
                        messages.forEachIndexed { index, message ->
                            key(message.id) {
                                if (message.isUser) {
                                    InboxOlfatoQuestion(text = message.content)
                                } else {
                                    InboxOlfatoReply(
                                        message = message,
                                        language = InboxOlfatoLanguage.ofReply(messages, index),
                                        last = index == messages.size - 1,
                                        canAsk = ready && !model.busy,
                                        panel = viewer.panel,
                                        retry = { model.retry() },
                                    ) { rating, reason ->
                                        model.rate(message.id, rating = rating, reason = reason)
                                    }
                                }
                            }
                        }
                    }
                    // iOS "olfato-bottom": the end the view follows.
                    Spacer(Modifier.height(1.dp))
                }
            }
            // The plain one (not the column's): it sits over the list, at the bottom of the box.
            androidx.compose.animation.AnimatedVisibility(
                below && !pinned && model.messages.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .cardShadow(CircleShape)
                        .background(Theme.surface, CircleShape)
                        .border(2.dp, Theme.blue, CircleShape)
                        .plainButton(label = L("Go to the latest message")) {
                            pinned = true
                            follow()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph("arrow-down", 16.dp, tint = Theme.blue)
                }
            }
        }

        // MARK: The box

        Box(
            Modifier
                .fillMaxWidth()
                .background(Theme.canvas)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
        ) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 768.dp)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (ready) {
                    val shape = RoundedCornerShape(Theme.cardRadius)
                    val questionLabel = L("Question for Olfato AI")
                    val prompt = pet?.let { LF("Ask about %@…", it.name) } ?: L("Ask about your patients or your business…")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (focused) Modifier.inboxFocusRing(Theme.mango.copy(alpha = 0.4f), Theme.cardRadius) else Modifier)
                            .cardShadow(shape)
                            .background(Theme.surface, shape)
                            .border(1.dp, if (problem != null || tooLong) Theme.destructive else Theme.borderSoft, shape)
                            .padding(start = 4.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = {
                                draft = it
                                if (problem != null) problem = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focused = it.isFocused }
                                .semantics { contentDescription = questionLabel }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            textStyle = Theme.bodyFont.copy(color = Theme.inkStrong),
                            minLines = 1,
                            maxLines = 6,
                            cursorBrush = SolidColor(Theme.blue),
                            decorationBox = { inner ->
                                Box {
                                    if (draft.isEmpty()) Text(prompt, style = Theme.bodyFont, color = Theme.inkSoft)
                                    inner()
                                }
                            },
                        )
                        if (model.busy) {
                            InboxRoundButton("square", label = L("Stop the reply"), filled = false) { scope.launch { model.stop() } }
                        } else {
                            InboxRoundButton("arrow-up", label = L("Send@olfato"), filled = true, enabled = draft.nonEmpty != null) { ask(draft) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                        val shownProblem = problem
                        // The line takes the room the count leaves (SwiftUI `Spacer(minLength: 8)` between them).
                        if (shownProblem != null) {
                            Text(shownProblem, Modifier.weight(1f), style = Theme.text(12, FontWeight.SemiBold), color = Theme.destructive)
                        } else {
                            Text(L("Olfato AI can get things wrong. Check the record before acting."), Modifier.weight(1f), style = Theme.text(12), color = Theme.inkSoft)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (draftCount > (maxChars.toDouble() * 0.8).toInt()) {
                            Text(
                                LF("%d of %d characters", draftCount, maxChars),
                                style = Theme.text(12, if (tooLong) FontWeight.Bold else FontWeight.Normal),
                                color = if (tooLong) Theme.destructive else Theme.inkSoft,
                            )
                        }
                    }
                } else {
                    InboxOlfatoLockedBar(gate = gate, canEditWorkspace = canEditWorkspace)
                }
            }
            RowDivider(modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

// MARK: A chat not begun

@Composable
private fun InboxOlfatoFresh(
    model: InboxOlfatoChatModel,
    viewer: InboxOlfatoViewer,
    pet: InboxOlfatoPetRef?,
    compact: Boolean,
    ready: Boolean,
    regular: Boolean,
    ask: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.padding(top = if (compact) 4.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InboxOlfatoArtwork("OlfatoAI", Modifier.size(if (compact) 56.dp else 80.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    pet?.let { LF("What do you want to know about %@?", it.name) } ?: L("What do you want to know?"),
                    style = Theme.heading(if (compact) 20 else 24),
                    color = Theme.inkStrong,
                )
                Text(
                    pet?.let { LF("I read %@’s chart with your access. I only read: I never change anything.", it.name) }
                        ?: L("Ask about your patients or your business. I answer only from what you can already see, and I never change anything."),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
        }
        viewer.panel?.let { InboxOlfatoAccessPanel(panel = it, compact = compact) }
        val suggestions = InboxOlfatoSuggestions.list(viewer.panel, pet = pet)
        if (ready && suggestions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("Try asking").uppercase(), style = brandText(12, FontWeight.Bold).tracking(0.3), color = Theme.inkSoft)
                val columns = if (regular) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { text ->
                                PMButton(text, variant = ButtonVariant.outline, fullWidth = true, modifier = Modifier.weight(1f), enabled = !model.busy) { ask(text) }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRoundButton(systemImage: String, label: String, filled: Boolean, enabled: Boolean = true, action: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .background(if (filled) Theme.blue else Theme.surface, CircleShape)
            .then(if (!filled) Modifier.border(2.dp, Theme.blue, CircleShape) else Modifier)
            .clip(CircleShape)
            .plainButton(enabled = enabled, label = label, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(systemImage, if (filled) 16.dp else 14.dp, tint = if (filled) Theme.onBlue else Theme.blue)
    }
}

/** In the box's place: earlier chats stay readable, nothing new is asked. */
@Composable
fun InboxOlfatoLockedBar(gate: InboxOlfatoGate, canEditWorkspace: Boolean, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.surfaceMuted, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Glyph("lock", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
            Text(gate.sentence, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
        }
        if (gate == InboxOlfatoGate.off && canEditWorkspace) {
            PMButtonStyle(ButtonVariant.outline, ButtonSize.compact, onClick = { nav.push(ProviderRoute.section(ProviderSection.settings)) }) {
                PMButtonContent(L("Turn it on in Settings"), variant = ButtonVariant.outline, size = ButtonSize.compact)
            }
        }
    }
}

/**
 * "Lo que puedo ver contigo": the areas open (with what each is limited to), the areas closed with why,
 * and what every answer is limited to. Built by the server from the person's permissions.
 */
@Composable
fun InboxOlfatoAccessPanel(
    panel: InboxOlfatoPanel,
    /** In a pet's chat: one line, opened on demand. */
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val areas = panel.offeredAreas
    val counts = LF("%d open · %d closed", areas.open.size, areas.closed.size)
    Card(modifier = modifier) {
        if (compact) {
            val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(200, easing = EaseInOut), label = "access chevron")
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainButton { expanded = !expanded }
                    .semantics { selected = expanded },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InboxAccessHeading()
                Spacer(Modifier.weight(1f).widthIn(min = 6.dp))
                Text(counts, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
            AnimatedVisibility(
                expanded,
                enter = fadeIn(tween(200, easing = EaseInOut)) + expandVertically(tween(200, easing = EaseInOut)),
                exit = fadeOut(tween(200, easing = EaseInOut)) + shrinkVertically(tween(200, easing = EaseInOut)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { InboxAccessDetails(panel, areas) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InboxAccessHeading()
                Spacer(Modifier.weight(1f))
                Text(counts, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
            }
            InboxAccessDetails(panel, areas)
        }
    }
}

@Composable
private fun InboxAccessHeading() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        InboxOlfatoArtwork(
            "OlfatoFace",
            Modifier.size(28.dp).background(Theme.wash(Theme.aqua, 24.0), CircleShape).padding(2.dp),
        )
        Text(L("What I can see with you"), style = Theme.heading(16), color = Theme.inkStrong)
    }
}

/** The panel's lines, straight into the card's column (12 apart). */
@Composable
private fun InboxAccessDetails(panel: InboxOlfatoPanel, areas: InboxOlfatoPanel.OfferedAreas) {
    (panel.scope ?: emptyList()).forEach { note ->
        CompositionLocalProvider(LocalContentColor provides Theme.inkStrong) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Glyph("map-pin", 14.dp, modifier = Modifier.padding(top = 2.dp))
                Text(note.text, style = Theme.text(14, FontWeight.SemiBold))
            }
        }
    }
    if (areas.open.isNotEmpty()) {
        Text(L("I can see").uppercase(), style = brandText(12, FontWeight.Bold).tracking(0.3), color = Theme.inkSoft)
        val done = ProviderState.done.color
        FlowLayout(spacing = 6.dp) {
            areas.open.forEach { area ->
                val label = (listOf(area.label?.text ?: area.key) + (area.notes ?: emptyList()).map { it.text }).joinToString(" · ")
                Row(
                    Modifier
                        .heightIn(min = 28.dp)
                        .background(Theme.wash(done, 10.0), RoundedCornerShape(50))
                        .border(1.dp, done, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("check", 12.dp, tint = Theme.inkStrong)
                    Text(label, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
                }
            }
        }
    }
    if (areas.closed.isNotEmpty()) {
        Text(L("Closed with your access").uppercase(), style = brandText(12, FontWeight.Bold).tracking(0.3), color = Theme.inkSoft)
        val waiting = ProviderState.waiting.color
        val soft = Theme.inkSoft
        val shape = RoundedCornerShape(Theme.boxRadius)
        areas.closed.forEach { area ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(waiting, 10.0), shape)
                    .border(1.dp, waiting, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Glyph("lock", 14.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(area.label?.text ?: area.key) }
                        area.reason?.let { reason -> withStyle(SpanStyle(color = soft)) { append(": ${reason.text}") } }
                    },
                    style = Theme.text(14),
                    color = Theme.inkStrong,
                )
            }
        }
    }
}

/** Questions to start from, only from the areas open to this person (lib/olfato-business-suggestions.ts). */
object InboxOlfatoSuggestions {
    private val business: List<Pair<String, String>> = listOf(
        "today" to "What do we have today?",
        "finances" to "How much did we collect this week?",
        "front_desk" to "Who owes us money?",
        "inventory" to "What is running low?",
        "hospital" to "Who is in the hospital right now?",
        "agenda" to "How many visits do we have this week?",
        "follow_ups" to "Which follow-ups are still to do?",
        "incidents" to "What incidents were there this week?",
        "stays" to "Who checks out of their stay today?",
        "grooming" to "How is grooming going today?",
        "pharmacy" to "Which prescriptions are waiting?",
        "clients" to "How many patients do we have?",
        "team" to "Who is working today?",
    )

    /** About one pet (from its chart). */
    private val pet: List<Pair<String, String>> = listOf(
        "records" to "What did we give %@ last time?",
        "records" to "Does %@ have anything pending?",
        "records" to "How has %@’s weight changed?",
        "clients" to "When was %@’s last visit?",
        "front_desk" to "Does %@’s family owe anything?",
    )

    fun list(panel: InboxOlfatoPanel?, max: Int = 4): List<String> = list(panel, pet = null, max = max)

    fun list(panel: InboxOlfatoPanel?, pet: InboxOlfatoPetRef?, max: Int = 4): List<String> {
        val open = (panel?.areas ?: emptyList()).filter { it.open == true && it.offered == true }.map { it.key }.toSet()
        if (pet == null) return business.filter { it.first in open }.take(max).map { L(it.second) }
        return this.pet.filter { it.first in open }.take(max).map { LF(it.second, pet.name) }
    }
}

// MARK: - A question and a reply

@Composable
private fun InboxOlfatoQuestion(text: String) {
    // The web's bubble: rounded-[1.25rem] with rounded-br-md, aqua 22%, 15.2 type.
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
    Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.End) {
        SelectionContainer {
            Text(
                text,
                Modifier.background(Theme.wash(Theme.aqua, 22.0), shape).padding(horizontal = 16.dp, vertical = 10.dp),
                style = Theme.text(15.2),
                color = Theme.inkStrong,
            )
        }
    }
}

/** Why a reply was bad, from the server's fixed list (web olfato-business-reply.tsx REASONS). */
private val inboxReplyReasons: List<Pair<String, String>> = listOf(
    "incorrect" to "It is wrong",
    "incomplete" to "Something is missing",
    "closed_wrongly" to "It said I had no access, and I do",
    "unhelpful" to "It did not help",
    "other" to "Something else",
)

/** iOS `.opacity(arriving ? 0.6 : 1).animation(.easeInOut(duration: 0.8).repeatForever())`: a slow pulse while it writes. */
@Composable
private fun inboxPulse(active: Boolean): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = "olfato pulse")
    val value by transition.animateFloat(1f, 0.6f, infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse), label = "olfato pulse")
    return value
}

@Composable
fun InboxOlfatoReply(
    message: InboxOlfatoMessage,
    /** The language the reply is written in (its question's): its tools, lock chips and links follow it. */
    language: InboxOlfatoLanguage = InboxOlfatoLanguage.app,
    last: Boolean,
    canAsk: Boolean,
    panel: InboxOlfatoPanel?,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
    rate: suspend (String?, String?) -> Unit,
) {
    val nav = LocalNavigator.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var askingWhy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<String?>(null) }
    var reasonMissing by remember { mutableStateOf(false) }

    fun byArea(status: String): List<InboxOlfatoTool> {
        val seen = LinkedHashMap<String, InboxOlfatoTool>()
        for (tool in message.tools) {
            if (tool.status != status) continue
            val key = tool.area ?: tool.name ?: ""
            if (key !in seen) seen[key] = tool
        }
        return seen.values.toList()
    }

    fun save(rating: String?, why: String?) {
        saving = true
        scope.launch {
            try {
                rate(rating, why)
                askingWhy = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(L("It could not be saved. Try again."))
            } finally {
                saving = false
            }
        }
    }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        val pulse = inboxPulse(message.arriving)
        InboxOlfatoArtwork(
            "OlfatoFace",
            Modifier
                .graphicsLayer { alpha = pulse }
                .size(32.dp)
                .background(Theme.blueWash, CircleShape)
                .padding(3.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val running = message.tools.filter { it.status == "running" }
            if (running.isNotEmpty()) {
                FlowLayout(spacing = 6.dp) {
                    running.forEach { tool ->
                        Row(
                            Modifier.background(Theme.surfaceMuted, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(10.dp), color = Theme.inkSoft, strokeWidth = 1.5.dp)
                            Text(language.format("Checking %@…", tool.areaName(language)), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
                        }
                    }
                }
            }
            if (message.arriving && message.content.isEmpty() && running.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                    Text(L("Olfato AI is thinking"), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
            if (message.content.isNotEmpty()) {
                InboxReplyText(content = message.content)
            }
            if (!message.arriving) {
                // MARK: Settled
                if (message.status == InboxOlfatoMessage.Status.stopped) {
                    CompositionLocalProvider(LocalContentColor provides Theme.inkSoft, LocalTextStyle provides Theme.text(12, FontWeight.SemiBold)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("circle-stop", 14.dp)
                            Text(L("Reply stopped."))
                        }
                    }
                }
                if (message.truncated) {
                    CompositionLocalProvider(LocalContentColor provides Theme.inkSoft, LocalTextStyle provides Theme.text(12, FontWeight.SemiBold)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                            Glyph("triangle-alert", 14.dp, modifier = Modifier.padding(top = 1.dp))
                            Text(L("The reply was cut short. Answer again to get it whole."))
                        }
                    }
                }
                if (message.status == InboxOlfatoMessage.Status.error) {
                    NoticeCard(tone = NoticeCard.Tone.problem, title = InboxOlfatoRefusal.sentence(message.errorCode, message.errorData))
                }
                val closed = byArea("closed")
                if (closed.isNotEmpty()) {
                    FlowLayout(spacing = 6.dp) {
                        closed.forEach { tool ->
                            val why = panel?.areas?.firstOrNull { it.key == tool.area }?.reason?.text(language)
                            StatusChip(
                                text = language.format("I couldn’t see %@ with your access", tool.areaName(language)),
                                state = ProviderState.waiting,
                                outlined = true,
                                modifier = Modifier.semantics { if (why != null) stateDescription = why },
                            )
                        }
                    }
                }
                val failed = byArea("failed")
                if (failed.isNotEmpty()) {
                    Text(
                        language.format("I couldn’t read %@ this time.", failed.joinToString(", ") { it.areaName(language) }),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                    )
                }
                val read = byArea("done")
                if (read.isNotEmpty()) {
                    Text(language.format("Read: %@", read.joinToString(", ") { it.areaName(language) }), style = Theme.text(12), color = Theme.inkSoft)
                }
                val links = message.links.mapNotNull { link -> link.route?.let { link to it } }
                if (links.isNotEmpty()) {
                    FlowLayout(spacing = 8.dp) {
                        links.forEach { (link, route) ->
                            PMButtonStyle(ButtonVariant.outline, ButtonSize.compact, onClick = { nav.push(route) }) {
                                PMButtonContent(
                                    link.label?.text(language).nonEmpty ?: language.text("Open@olfato"),
                                    trailingIcon = "arrow-right",
                                    variant = ButtonVariant.outline,
                                    size = ButtonSize.compact,
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (message.content.isNotEmpty()) {
                        IconCircleButton(systemImage = if (copied) "check" else "copy", label = L("Copy reply")) {
                            clipboard.setText(AnnotatedString(InboxReplyText.plain(message.content)))
                            copied = true
                            scope.launch {
                                delay(1600)
                                copied = false
                            }
                        }
                    }
                    if (last && canAsk && InboxOlfatoRefusal.retryHelps(message.errorCode)) {
                        IconCircleButton(systemImage = "refresh-cw", label = L("Answer again"), action = retry)
                    }
                    if (message.status == InboxOlfatoMessage.Status.done && !message.local) {
                        val rating = message.feedback?.rating
                        IconCircleButton(
                            systemImage = "thumbs-up",
                            label = L("Good reply"),
                            tint = if (rating == "up") Theme.blue else Theme.inkSoft,
                            enabled = !saving,
                        ) {
                            save(if (rating == "up") null else "up", null)
                        }
                        IconCircleButton(
                            systemImage = "thumbs-down",
                            label = L("Bad reply"),
                            tint = if (rating == "down") Theme.blue else Theme.inkSoft,
                            enabled = !saving,
                        ) {
                            if (rating == "down") {
                                save(null, null)
                            } else {
                                reason = null
                                reasonMissing = false
                                askingWhy = !askingWhy
                            }
                        }
                    }
                }
                // Down asks why under the reply, in a small box (web: the inline form, not a dialog).
                if (askingWhy) {
                    val shape = RoundedCornerShape(Theme.boxRadius)
                    val whyLabel = L("What was wrong with it?")
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Theme.surface, shape)
                            .border(1.dp, Theme.borderSoft, shape)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(whyLabel, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                        FlowLayout(spacing = 6.dp, modifier = Modifier.semantics { contentDescription = whyLabel }) {
                            inboxReplyReasons.forEach { (value, title) ->
                                FilterChip(title = L(title), isSelected = reason == value, solid = true) {
                                    reason = value
                                    reasonMissing = false
                                }
                            }
                        }
                        if (reasonMissing && reason == null) {
                            Text(L("Choose what was wrong with the reply."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Spacer(Modifier.weight(1f))
                            PMButton(L("Cancel"), variant = ButtonVariant.outline, size = ButtonSize.compact) { askingWhy = false }
                            PMButton(if (saving) L("Saving…") else L("Send"), size = ButtonSize.compact, isWorking = saving, enabled = !saving) {
                                val chosen = reason
                                if (chosen == null) {
                                    reasonMissing = true
                                    Toast.error(L("Choose what was wrong with the reply."))
                                } else {
                                    save("down", chosen)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A reply read into paragraphs, headings, lists, table rows and **bold** — text only, never HTML
 * (olfato-business/business-reply-text.ts).
 */
object InboxReplyText {
    data class Span(val text: String, val bold: Boolean)

    sealed class Block {
        data class paragraph(val spans: List<Span>) : Block()
        data class heading(val spans: List<Span>) : Block()
        data class list(val ordered: Boolean, val items: List<List<Span>>) : Block()
        data class table(val rows: List<List<List<Span>>>) : Block()
    }

    private val openingMark = Regex("(^|\\s)[_*](\\S)")
    private val closingMark = Regex("(\\S)[_*](?=\\s|$|[.,;:!?])")
    private const val bullet = "^(?:[-*•]\\s+|[-*•]$)"
    private const val numbered = "^\\d{1,3}[.)]\\s+"
    private const val heading = "^#{1,6}\\s+"
    private const val rule = "^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$"
    private val patterns = HashMap<String, Regex>()

    private fun regex(pattern: String): Regex = patterns.getOrPut(pattern) { Regex(pattern) }

    /** Swift `trimmingCharacters(in: .whitespaces)`: spaces and tabs, not line breaks. */
    private fun trimSpaces(text: String): String = text.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

    /** One line's pieces. A `**` still open (the reply is arriving) is bold to the end. */
    fun spans(line: String): List<Span> =
        line.split("**").mapIndexedNotNull { index, part ->
            var text = openingMark.replace(part, "$1$2")
            text = closingMark.replace(text, "$1")
            text = text.replace("`", "")
            if (text.isEmpty()) null else Span(text, bold = index % 2 == 1)
        }

    private fun matches(line: String, pattern: String): Boolean = regex(pattern).containsMatchIn(line)

    private fun strip(line: String, pattern: String): String = regex(pattern).replace(line, "")

    fun blocks(content: String): List<Block> {
        val blocks = mutableListOf<Block>()
        for (raw in content.split("\n")) {
            val line = trimSpaces(raw)
            if (line.isEmpty()) continue
            if (matches(line, rule) && line.contains("-")) continue
            if (line.startsWith("|")) {
                var trimmed = line
                if (trimmed.startsWith("|")) trimmed = trimmed.drop(1)
                if (trimmed.endsWith("|")) trimmed = trimmed.dropLast(1)
                val row = trimmed.split("|").map { spans(trimSpaces(it)) }
                val previous = blocks.lastOrNull()
                if (previous is Block.table) {
                    blocks[blocks.size - 1] = Block.table(previous.rows + listOf(row))
                } else {
                    blocks.add(Block.table(listOf(row)))
                }
            } else if (matches(line, bullet) || matches(line, numbered)) {
                val ordered = matches(line, numbered)
                val item = spans(strip(line, if (ordered) numbered else bullet))
                val previous = blocks.lastOrNull()
                if (previous is Block.list && previous.ordered == ordered) {
                    blocks[blocks.size - 1] = Block.list(ordered, previous.items + listOf(item))
                } else {
                    blocks.add(Block.list(ordered, listOf(item)))
                }
            } else if (matches(line, heading)) {
                blocks.add(Block.heading(spans(strip(line, heading))))
            } else {
                blocks.add(Block.paragraph(spans(line)))
            }
        }
        return blocks
    }

    /** A reply as plain text, for copying. */
    fun plain(content: String): String =
        content.replace("**", "").replace(Regex("(?m)^#{1,6}\\s+"), "")

    fun text(spans: List<Span>): AnnotatedString = buildAnnotatedString {
        spans.forEach { span ->
            if (span.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) } else append(span.text)
        }
    }
}

@Composable
fun InboxReplyText(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { InboxReplyText.blocks(content) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is InboxReplyText.Block.paragraph ->
                        Text(InboxReplyText.text(block.spans), style = Theme.bodyFont, color = Theme.inkStrong)
                    is InboxReplyText.Block.heading ->
                        Text(InboxReplyText.text(block.spans), Modifier.padding(top = 2.dp), style = Theme.heading(17), color = Theme.inkStrong)
                    is InboxReplyText.Block.list -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEachIndexed { index, item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (block.ordered) "${index + 1}." else "•", Modifier.alignByBaseline(), style = Theme.bodyFont, color = Theme.inkSoft)
                                Text(InboxReplyText.text(item), Modifier.alignByBaseline(), style = Theme.bodyFont, color = Theme.inkStrong)
                            }
                        }
                    }
                    is InboxReplyText.Block.table -> InboxReplyTable(block.rows.map { row -> row.map { InboxReplyText.text(it) } })
                }
            }
        }
    }
}

/**
 * SwiftUI `Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6)` in a sideways
 * scroll: each column as wide as its widest cell, a rule between rows, the head row semibold.
 */
@Composable
private fun InboxReplyTable(rows: List<List<AnnotatedString>>) {
    val shape = RoundedCornerShape(Theme.chipRadius)
    val ruleColor = Theme.borderSoft
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Layout(
                content = {
                    rows.forEachIndexed { index, row ->
                        row.forEach { cell ->
                            Text(
                                cell,
                                Modifier.layoutId("cell"),
                                style = Theme.text(14, if (index == 0) FontWeight.SemiBold else FontWeight.Normal),
                                color = Theme.inkStrong,
                                softWrap = false,
                            )
                        }
                        if (index < rows.size - 1) Box(Modifier.layoutId("rule").height(1.dp).background(ruleColor))
                    }
                },
            ) { measurables, _ ->
                val hSpacing = 12.dp.roundToPx()
                val vSpacing = 6.dp.roundToPx()
                var at = 0
                val cells = mutableListOf<List<Placeable>>()
                val ruleMeasurables = mutableListOf<androidx.compose.ui.layout.Measurable>()
                rows.forEachIndexed { index, row ->
                    cells += measurables.subList(at, at + row.size).map { it.measure(Constraints()) }
                    at += row.size
                    if (index < rows.size - 1) {
                        ruleMeasurables += measurables[at]
                        at += 1
                    }
                }
                val columns = rows.maxOfOrNull { it.size } ?: 0
                val widths = IntArray(columns)
                cells.forEach { row -> row.forEachIndexed { c, placeable -> widths[c] = max(widths[c], placeable.width) } }
                val total = widths.sum() + hSpacing * max(0, columns - 1)
                val rules = ruleMeasurables.map { it.measure(Constraints.fixed(total, 1.dp.roundToPx())) }
                val heights = cells.map { row -> row.maxOfOrNull { it.height } ?: 0 }
                val gridRows = cells.size + rules.size
                val height = heights.sum() + rules.sumOf { it.height } + vSpacing * max(0, gridRows - 1)
                layout(total, height) {
                    var y = 0
                    cells.forEachIndexed { index, row ->
                        var x = 0
                        row.forEachIndexed { c, placeable ->
                            placeable.placeRelative(x, y + (heights[index] - placeable.height) / 2)
                            x += widths[c] + hSpacing
                        }
                        y += heights[index] + vSpacing
                        if (index < rules.size) {
                            rules[index].placeRelative(0, y)
                            y += rules[index].height + vSpacing
                        }
                    }
                }
            }
        }
    }
}
