package com.pawmiaw.business

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

//
//  The system share sheet for a file the app made (a CSV export, a template) or a link
//  (iOS ShareSheet.swift, and SwiftUI's `ShareLink`). Android's chooser over `ACTION_SEND`;
//  files go through the app's FileProvider (`${applicationId}.files`, cache/exports).
//
//      .sheet(item: $exported) { ShareSheet(items: [$0.url]) }
//          → exported?.let { ShareSheet(listOf(it.file), onDismiss = { exported = null }) }   (no AppSheet around it)
//      ShareLink(item: url) { GlyphLabel(L("Share link"), icon: "share") }
//          → ShareLink(url) { GlyphLabel(L("Share link"), "share") }
//      a ShareLink in a Menu → item(L("Share link"), icon = "share") { shareItems(context, listOf(url)) }
//
//  Items: a `File`, a content `Uri`, or text (a link, a sentence; also `java.net.URL`/`URI`).
//

/** iOS `ShareSheet(items:)`: opens the chooser when composed; `onDismiss` when it closes. */
@Composable
fun ShareSheet(items: List<Any>, onDismiss: () -> Unit = {}) {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDismiss)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { done() }
    LaunchedEffect(Unit) {
        val chooser = shareIntent(context, items)
        if (chooser == null) done() else runCatching { launcher.launch(chooser) }.onFailure { done() }
    }
}

/** SwiftUI `ShareLink(item:preview:) { label }`: the label, tappable, sharing `item`. */
@Composable
fun ShareLink(item: Any, modifier: Modifier = Modifier, preview: String? = null, label: @Composable () -> Unit) {
    val context = LocalContext.current
    Box(modifier.plainButton { shareItems(context, listOf(item), preview) }) { label() }
}

/** Opens the share chooser for `items` now (from a menu row, a callback). */
fun shareItems(context: Context, items: List<Any>, title: String? = null) {
    val chooser = shareIntent(context, items, title) ?: return
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}

/** The chooser over `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for `items`; null when there is nothing to share. */
fun shareIntent(context: Context, items: List<Any>, title: String? = null): Intent? {
    val streams = ArrayList<Uri>()
    val texts = ArrayList<String>()
    val types = ArrayList<String>()
    for (item in items) {
        when (item) {
            is File -> {
                streams.add(fileUri(context, item))
                types.add(mimeType(item.name))
            }
            is Uri -> if (item.scheme == "content" || item.scheme == "file") {
                streams.add(item)
                types.add(context.contentResolver.getType(item) ?: mimeType(item.lastPathSegment ?: ""))
            } else {
                texts.add(item.toString())
            }
            else -> item.toString().takeIf { it.isNotBlank() }?.let { texts.add(it) }
        }
    }
    if (streams.isEmpty() && texts.isEmpty()) return null
    val send = Intent(if (streams.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        type = when {
            streams.isEmpty() -> "text/plain"
            types.distinct().size == 1 -> types.first()
            else -> "*/*"
        }
        if (texts.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, texts.joinToString("\n"))
        if (title != null) {
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
        }
        when (streams.size) {
            0 -> Unit
            1 -> putExtra(Intent.EXTRA_STREAM, streams.first())
            else -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
        }
        if (streams.isNotEmpty()) {
            clipData = ClipData.newRawUri(title ?: "", streams.first()).also { clip -> streams.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    return Intent.createChooser(send, title).apply {
        if (streams.isNotEmpty()) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** A file the app made, as the content address other apps may read (FileProvider). */
fun fileUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

/** "application/pdf" for "Recibo.pdf", "text/csv" for a CSV. */
fun mimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> "application/pdf"
        "csv" -> "text/csv"
        "" -> "application/octet-stream"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
