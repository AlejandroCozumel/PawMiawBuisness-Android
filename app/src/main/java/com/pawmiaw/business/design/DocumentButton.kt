package com.pawmiaw.business

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

//
//  A file the server makes (a receipt PDF, a report CSV, a prescription): downloaded with the
//  session, then shown in the phone's viewer, which shares and prints it (iOS
//  DocumentButton.swift: Quick Look). The one way the app opens a server document.
//

@Composable
fun DocumentButton(
    title: String,
    systemImage: String = "doc.richtext",
    /** The API path (`/provider-store/sales/{id}/pdf`). */
    path: String,
    query: Map<String, String?> = emptyMap(),
    /** The name people see when they share it ("Recibo V-000123"). */
    fileName: String,
    /** "pdf" or "csv". */
    ext: String = "pdf",
    variant: ButtonVariant = ButtonVariant.outline,
    size: ButtonSize = ButtonSize.compact,
    fullWidth: Boolean = false,
    /** Runs before the download (a bill makes its PDF first). */
    prepare: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    PMButton(title, systemImage = systemImage, variant = variant, size = size, fullWidth = fullWidth, isWorking = working, modifier = modifier) {
        working = true
        scope.launch {
            try {
                prepare?.invoke()
                val file = API.download(path, query, fileName = fileName, ext = ext)
                quickLookPreview(context, file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(failure, fallback = L("The file could not be opened. Try again."))
            } finally {
                working = false
            }
        }
    }
}

/**
 * iOS `.quickLookPreview(url)`: a file the app made, in the phone's viewer for its kind (which
 * shares and prints it); with no viewer for it (a CSV on many phones), the share sheet.
 */
fun quickLookPreview(context: Context, file: File) {
    val uri = fileUri(context, file)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType(file.name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(view)
    } catch (missing: ActivityNotFoundException) {
        shareItems(context, listOf(file), file.name)
    }
}
