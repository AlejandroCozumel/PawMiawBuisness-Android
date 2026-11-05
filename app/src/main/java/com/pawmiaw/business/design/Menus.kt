package com.pawmiaw.business

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

//
//  The app's menus: SwiftUI `Menu { … } label: { … }` on Android (filters, a row's ⋯, the
//  account menu, a field's options). The label is drawn as it is and opens a drop-down
//  anchored under it: the surface, 16 corners, a hairline, a soft shadow (web §9.8); rows 44
//  tall, 14 semibold, a 16 icon before the words, a press in mango with cocoa words (the web's
//  `--accent`), red words for a destructive one, a blue ✓ after the chosen one.
//
//  Swift → Kotlin:
//
//      Menu {                                          AppMenu(label = { IconCircleLabel() }, accessibilityLabel = LF("More for %@", name)) {
//          Button { edit() } label: {                      item(L("Edit address"), icon = "pencil") { edit() }
//              GlyphLabel(L("Edit address"), icon: "pencil") }
//          Divider()                                       divider()
//          Button(role: .destructive) { remove() }         item(L("Remove address"), icon = "archive", destructive = true) { remove() }
//              label: { GlyphLabel(…, icon: "archive") }
//      } label: { IconCircleLabel() }                  }
//      .disabled(busy)                                 (AppMenu(…, enabled = !busy))
//
//      Section(user.email) { Button … }               section(user.email); item(…)          (a header; a rule above it)
//      Section { … }                                   section()                             (a rule only)
//      Picker(L("Sort"), selection: $sort) {           picker(L("Sort"), options = listOf(ClientsSort.recent to L("Recently added"), …), selection = sort)
//          Text(…).tag(…) }                            (inline: the options with a ✓ on the chosen, as iOS shows a Picker in a Menu)
//      Picker(selection:) { … } label: {               picker(L("Theme"), icon = "sun", options = …, selection = theme, inline = false)
//          GlyphLabel(L("Theme"), icon: "sun") }       (one row with the chosen value that opens its options in place:
//          .pickerStyle(.menu)                          iOS's submenu)
//      Button { … } label: { if chosen { GlyphLabel(t, icon: "check") } else { Text(t) } }
//                                                      item(t, checked = chosen) { … }
//      ShareLink(item: url) { GlyphLabel(…) }          item(L("Share link"), icon = "share") { shareItems(context, listOf(url)) }
//      Menu { … } label: { PMButtonContent(…) }.buttonStyle(.pm(.outline, size: .compact))
//                                                      AppMenu(label = { PMButtonStyle(ButtonVariant.outline, ButtonSize.compact) { PMButtonContent(…) } }) { … }
//
//  The label is a look, not a control: a ⋯ is `IconCircleLabel()` (not an `IconCircleButton`
//  with `.allowsHitTesting(false)`), a select is `PillField`/`FilterMenuLabel`, a button is
//  `PMButtonStyle { PMButtonContent(…) }` without `onClick`.
//  A standalone `Picker(...).pickerStyle(.menu)` in a form is `MenuField`. Any other row can be
//  drawn inside the content too (it is a composable scope); call `dismiss()` to close the menu.
//  Rules collapse: none at the top or the bottom, never two in a row.
//

/** What an `AppMenu`'s content can draw: rows, section headers, rules and inline pickers. */
@Stable
class AppMenuScope(private val close: () -> Unit) {
    /** Closes the menu (a custom row's action). */
    fun dismiss() = close()

    /** A row (SwiftUI `Button` in a `Menu`): the menu closes, then `onClick` runs. */
    @Composable
    fun item(
        title: String,
        icon: String? = null,
        destructive: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        MenuRow(title, icon, destructive, checked, enabled) {
            close()
            onClick()
        }
    }

    /** SwiftUI `Section(title)` in a `Menu`: a rule, then the title as a small grey header. */
    @Composable
    fun section(title: String? = null) {
        MenuRule()
        if (title != null) MenuHeader(title)
    }

    /** SwiftUI `Divider()` in a `Menu`. */
    @Composable
    fun divider() = MenuRule()

    /**
     * SwiftUI `Picker` in a `Menu`. `inline` (iOS's default there): its options as rows between
     * rules, a ✓ on the chosen one; the title isn't shown (as on iOS). `inline = false`
     * (`.pickerStyle(.menu)`): one row (icon, title, the chosen value) that opens the options
     * under it. Choosing sets `selection`, runs `onChange` and closes the menu.
     */
    @Composable
    fun <T> picker(
        title: String?,
        icon: String? = null,
        options: List<Pair<T, String>>,
        selection: MutableState<T>,
        inline: Boolean = true,
        onChange: ((T) -> Unit)? = null,
    ) {
        fun choose(value: T) {
            close()
            selection.value = value
            onChange?.invoke(value)
        }
        if (inline) {
            MenuRule()
            options.forEach { (value, words) ->
                MenuRow(words, null, destructive = false, checked = selection.value == value, enabled = true) { choose(value) }
            }
            MenuRule()
        } else {
            var open by remember { mutableStateOf(false) }
            val chosen = options.firstOrNull { it.first == selection.value }?.second
            MenuRow(
                title ?: "",
                icon,
                destructive = false,
                checked = false,
                enabled = true,
                trailing = {
                    if (chosen != null) Text(chosen, style = Theme.text(13, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1)
                    Glyph(if (open) "chevron-down" else "chevron-right", 16.dp, tint = Theme.inkSoft)
                },
                closes = false,
            ) { open = !open }
            if (open) {
                options.forEach { (value, words) ->
                    MenuRow(words, null, destructive = false, checked = selection.value == value, enabled = true, indent = if (icon != null) 26.dp else 12.dp) { choose(value) }
                }
            }
        }
    }
}

/**
 * SwiftUI `Menu { content } label: { label }`: the label as it is (dimmed while pressed unless
 * it is a `PMButtonStyle`, which shows its own press), opening the menu under it.
 * `accessibilityLabel` replaces what the label reads out.
 */
@Composable
fun AppMenu(
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    content: @Composable AppMenuScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val press = remember { PressSource(interaction) }
    val pressed by interaction.collectIsPressedAsState()
    val isEnabled = enabled && LocalIsEnabled.current
    val scope = remember { AppMenuScope { expanded = false } }
    Box(
        modifier
            .graphicsLayer { alpha = if (pressed && !press.styled) 0.6f else 1f }
            .then(if (accessibilityLabel != null) Modifier.semantics { contentDescription = accessibilityLabel } else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = isEnabled, role = Role.DropdownList) { expanded = true },
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalPressSource provides press, LocalIsEnabled provides isEnabled) { label() }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(Theme.boxRadius),
            containerColor = Theme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, Theme.borderSoft),
        ) {
            MenuColumn(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) { scope.content() }
        }
    }
}

private const val RULE_ID = "app-menu-rule"

/** The menu's rows, top to bottom; a rule at the top, the bottom or after another is skipped. */
@Composable
private fun MenuColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val shown = BooleanArray(measurables.size)
        var lastShownIsRule = true // nothing above yet: a rule here would lead
        measurables.forEachIndexed { index, measurable ->
            val isRule = measurable.layoutId == RULE_ID
            if (isRule) {
                val anythingBelow = (index + 1 until measurables.size).any { measurables[it].layoutId != RULE_ID }
                if (!lastShownIsRule && anythingBelow) {
                    shown[index] = true
                    lastShownIsRule = true
                }
            } else {
                shown[index] = true
                lastShownIsRule = false
            }
        }
        val loose = constraints.copy(minHeight = 0)
        val placeables = measurables.mapIndexed { index, measurable -> if (shown[index]) measurable.measure(loose) else null }
        val width = placeables.maxOfOrNull { it?.width ?: 0 }?.coerceIn(constraints.minWidth, constraints.maxWidth) ?: constraints.minWidth
        val height = placeables.sumOf { it?.height ?: 0 }
        layout(width, height.coerceIn(constraints.minHeight, if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity)) {
            var y = 0
            placeables.forEach { placeable ->
                if (placeable != null) {
                    placeable.placeRelative(0, y)
                    y += placeable.height
                }
            }
        }
    }
}

@Composable
private fun MenuRule() {
    Box(
        Modifier
            .layoutId(RULE_ID)
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(Theme.borderSoft)
    )
}

@Composable
private fun MenuHeader(title: String) {
    Text(
        title,
        style = Theme.text(12, FontWeight.SemiBold),
        color = Theme.inkSoft,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 2.dp)
            .semantics { heading() },
    )
}

@Composable
private fun MenuRow(
    title: String,
    icon: String?,
    destructive: Boolean,
    checked: Boolean,
    enabled: Boolean,
    indent: Dp = 0.dp,
    trailing: (@Composable () -> Unit)? = null,
    closes: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = when {
        pressed -> Theme.cocoa
        destructive -> Theme.destructive
        else -> Theme.inkStrong
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Theme.chipRadius))
            .background(if (pressed) Theme.mango else Color.Transparent)
            .semantics { if (checked) selected = true }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = if (closes) Role.Button else Role.DropdownList, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .padding(start = 10.dp + indent, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Glyph(icon, 16.dp, tint = ink)
        Text(
            title,
            style = Theme.text(14, FontWeight.SemiBold),
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        trailing?.invoke()
        if (checked) Glyph("check", 16.dp, tint = if (pressed) Theme.cocoa else Theme.blue)
    }
}
