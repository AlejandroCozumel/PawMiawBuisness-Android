package com.pawmiaw.business.records

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

//
//  RecordsFiles.kt (iOS RecordsFiles.swift)
//
//  A record's photos and documents (web records-page/record-detail.tsx
//  AttachmentsCard). Files are read through the API with the session — the
//  download address needs it — then a photo shows as its thumbnail and any
//  file opens in the phone's viewer (iOS Quick Look).
//

class RecordsFileStore private constructor() {
    private val images = android.util.LruCache<String, ImageBitmap>(64)

    /** A photo's image, kept while the app runs. */
    suspend fun image(url: String): ImageBitmap? {
        images.get(url)?.let { return it }
        val data = try {
            RecordsAPI.fileData(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val image = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
        } ?: return null
        images.put(url, image)
        return image
    }

    /**
     * The file on this device (the app's own cache, under the folder the app's FileProvider
     * shares), for the phone's viewer.
     */
    suspend fun localFile(attachment: RecordsAttachment): File {
        val folder = File(AppContext.context.cacheDir, "exports/records-files/${attachment.id}")
        val file = File(folder, safeName(attachment))
        if (file.exists()) return file
        val data = RecordsAPI.fileData(attachment.url)
        withContext(Dispatchers.IO) {
            folder.mkdirs()
            val temporary = File(folder, file.name + ".part")
            temporary.writeBytes(data)
            temporary.renameTo(file)
        }
        return file
    }

    companion object {
        val shared = RecordsFileStore()

        private fun safeName(attachment: RecordsAttachment): String {
            var name = attachment.fileName.replace("/", "-").replace(":", "-")
            if (name.trim().isEmpty()) name = "file"
            if (!name.contains(".")) {
                val mime = attachment.mimeType?.lowercase()
                name += when {
                    mime == "application/pdf" -> ".pdf"
                    mime == "image/png" -> ".png"
                    mime == "image/heic" -> ".heic"
                    mime == "image/webp" -> ".webp"
                    mime == "image/gif" -> ".gif"
                    mime != null && mime.startsWith("image/") -> ".jpg"
                    else -> ""
                }
            }
            return name
        }
    }
}

/** A photo read with the session; a placeholder while it loads. */
@Composable
fun RecordsAuthImage(url: String, modifier: Modifier = Modifier) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        image = RecordsFileStore.shared.image(url)
        failed = image == null
    }
    Box(modifier.fillMaxSize().background(Theme.surfaceMuted), contentAlignment = Alignment.Center) {
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (failed) {
            Glyph("image", 22.dp, tint = Theme.inkSoft)
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
        }
    }
}
