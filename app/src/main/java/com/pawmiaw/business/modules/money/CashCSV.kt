package com.pawmiaw.business.money

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

//
//  CashCSV.kt (iOS CashCSV.swift)
//
//  Spreadsheets made from what the app read with the person's session (web:
//  lib/finance.ts `toCsv`, `exportFileName`, `downloadCsv`): UTF-8 with the
//  byte-order mark Excel needs for accents, comma-separated, CRLF lines.
//  On a phone the file is handed to the share sheet (Files, Mail, Drive…).
//

object CashCSV {
    /** One cell, quoted when it holds a separator, a quote or a line break. */
    fun cell(value: String?): String {
        val text = value ?: return ""
        if (text.any { it == '"' || it == ',' || it == '\n' || it == ';' || it == '\r' }) {
            return "\"" + text.replace("\"", "\"\"") + "\""
        }
        return text
    }

    /** Cents as a plain number spreadsheets read: 1234.50 */
    fun money(cents: Int?): String {
        if (cents == null) return ""
        val sign = if (cents < 0) "-" else ""
        val value = kotlin.math.abs(cents)
        return "$sign${value / 100}.${String.format(Locale.US, "%02d", value % 100)}"
    }

    fun make(header: List<String>, rows: List<List<String?>>): String =
        (listOf(header.map { cell(it) }) + rows.map { row -> row.map { cell(it) } }).joinToString("\r\n") { it.joinToString(",") }

    /** "pagos_2026-11-01_2026-11-30.csv": the slug of what it is and the days it covers. */
    fun fileName(what: String, from: String, to: String, ext: String = "csv"): String {
        val folded = Normalizer.normalize(what, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
        val slug = StringBuilder()
        var dash = false
        for (character in folded) {
            if (character.code < 128 && character.isLetterOrDigit()) {
                slug.append(character)
                dash = false
            } else if (!dash && slug.isNotEmpty()) {
                slug.append('-')
                dash = true
            }
        }
        while (slug.endsWith("-")) slug.setLength(slug.length - 1)
        return "${slug}_${if (from == to) from else "${from}_$to"}.$ext"
    }

    /** "2026-11-04 09:02" on the business's clock. */
    fun dateTime(iso: String?, zone: ZoneId? = null): String {
        val date = DateText.parse(iso) ?: return ""
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(zone ?: Workspace.shared.timeZone).format(date)
    }

    /** The file on this device, ready to share (the app's FileProvider shares `cache/exports`). */
    fun write(csv: String, name: String): File {
        val folder = File(AppContext.context.cacheDir, "exports/csv").apply { mkdirs() }
        val file = File(folder, name)
        file.writeText("﻿" + csv, Charsets.UTF_8)
        return file
    }
}

/** A file made, waiting for the share sheet. */
class CashSharedFile(val url: File) {
    val id: UUID = UUID.randomUUID()
}

/** "Exportar CSV": makes the file (it may read more pages first) and opens the share sheet. */
@Composable
fun CashCSVButton(
    title: String = L("Export CSV"),
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
    /** The file's name and its text. */
    make: suspend () -> Pair<String, String>,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<CashSharedFile?>(null) }

    fun export() {
        working = true
        scope.launch {
            try {
                val (name, csv) = make()
                file = CashSharedFile(withContext(Dispatchers.IO) { CashCSV.write(csv, name) })
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Toast.error(L("The file could not be made. Try again."))
            } finally {
                working = false
            }
        }
    }

    PMButton(title, systemImage = "download", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = working, enabled = !disabled, modifier = modifier) { export() }
    file?.let { shared -> key(shared.id) { ShareSheet(listOf(shared.url), onDismiss = { file = null }) } }
}
