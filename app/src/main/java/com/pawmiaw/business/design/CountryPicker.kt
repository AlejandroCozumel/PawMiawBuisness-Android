package com.pawmiaw.business

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

//
//  Choosing a country (iOS CountryPicker.swift): a field that shows the chosen one in the
//  app's language and opens a searchable list of every ISO country (accents and case ignored,
//  the two-letter code works too). Used by Home's "Confirma tu región y dinero" and Team's
//  Settings › Region and money. The list is a sheet of its own: Cancel, "Country", the search
//  bar, the countries in one card with a ✓ on the chosen one.
//

/** iOS `CountryPicker.name(_:)` and `CountryPicker.all`. */
object CountryPicker {
    /** A two-letter code in the app's language ("MX" → "México"). */
    fun name(code: String?): String? {
        val upper = code?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val shown = Locale("", upper).getDisplayCountry(appLocale)
        return if (shown.isNullOrEmpty()) upper else shown
    }

    /** Every country, by name in the app's language. */
    val all: List<Pair<String, String>>
        get() {
            val collator = Collator.getInstance(appLocale)
            return Locale.getISOCountries()
                .filter { it.length == 2 && it.all(Char::isLetter) }
                .map { it to (name(it) ?: it) }
                .sortedWith { a, b -> collator.compare(a.second, b.second) }
        }

    /** Accents and case ignored (Swift `.folding(options: [.diacriticInsensitive, .caseInsensitive])`). */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(appLocale)
}

/** The list (put it in an `AppSheet`): a tap picks the country and closes the sheet. */
@Composable
fun CountryPicker(selection: String, onPick: (String) -> Unit) {
    val dismiss = LocalDismiss.current
    val query = remember { mutableStateOf("") }
    val language = LocalizationManager.currentLanguage
    val countries = remember(language) { CountryPicker.all }
    val needle = CountryPicker.fold(query.value.trim())
    val shown = remember(countries, needle) {
        countries.filter { (code, name) -> needle.isEmpty() || CountryPicker.fold(name).contains(needle) || code.lowercase() == needle }
    }
    Column(Modifier.fillMaxWidth().sheetPresentation(setOf(PresentationDetent.large)).background(Theme.canvas)) {
        // The navigation bar: Cancel, the title inline.
        Box(Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 44.dp).padding(horizontal = 16.dp)) {
            Text(
                L("Cancel"),
                style = Theme.text(17),
                color = Theme.blue,
                modifier = Modifier.align(Alignment.CenterStart).plainButton { dismiss() },
            )
            Text(
                L("Country"),
                style = Theme.text(17, FontWeight.SemiBold),
                color = Theme.inkStrong,
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
            )
        }
        // `.searchable`: the search bar under the title.
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(36.dp)
                .background(SearchFill.resolve(), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("search", 16.dp, tint = Theme.inkSoft)
            Spacer(Modifier.width(6.dp))
            PlainTextField(
                query,
                L("Search"),
                Modifier.weight(1f),
                capitalization = KeyboardCapitalization.None,
                autocorrect = false,
                style = Theme.text(16).copy(color = Theme.inkStrong),
                imeAction = ImeAction.Search,
            )
            if (query.value.isNotEmpty()) {
                Glyph("circle-x", 16.dp, tint = Theme.inkSoft, modifier = Modifier.plainButton(label = L("Clear")) { query.value = "" })
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            itemsIndexed(shown, key = { _, country -> country.first }) { index, (code, name) ->
                val radius = Theme.boxRadius
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) radius else 0.dp,
                    topEnd = if (index == 0) radius else 0.dp,
                    bottomStart = if (index == shown.lastIndex) radius else 0.dp,
                    bottomEnd = if (index == shown.lastIndex) radius else 0.dp,
                )
                Column(Modifier.fillMaxWidth().background(Theme.surface, shape)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .semantics { if (code == selection) selected = true }
                            .plainButton {
                                onPick(code)
                                dismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = Theme.text(16), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                        if (code == selection) Glyph("check", 17.dp, tint = Theme.blue)
                    }
                    if (index != shown.lastIndex) {
                        Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Theme.borderSoft))
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** The search bar's grey (iOS `tertiarySystemFill`). */
private val SearchFill = SchemeColor(Color(118, 118, 128).copy(alpha = 0.12f), Color(118, 118, 128).copy(alpha = 0.24f))

/** The country as a form field: its name (or "Choose"), opening `CountryPicker`. */
@Composable
fun CountryField(
    label: String = L("Country"),
    code: String,
    key: String? = null,
    hint: String? = null,
    form: FormState? = null,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    FormField(label, key = key, hint = hint, form = form, modifier = modifier) {
        FieldChrome(invalid = key?.let { form?.error(it) } != null, modifier = Modifier.plainButton { picking = true }) {
            Text(
                CountryPicker.name(code) ?: L("Choose"),
                color = if (code.isEmpty()) Theme.inkSoft else Theme.inkStrong,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Glyph("chevron.up.chevron.down", 14.dp, tint = Theme.inkSoft)
        }
    }
    if (picking) {
        AppSheet(onDismissRequest = { picking = false }) {
            CountryPicker(selection = code) { picked ->
                if (key != null) form?.clear(key)
                onPick(picked)
            }
        }
    }
}
