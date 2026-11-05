package com.pawmiaw.business

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

//
//  One field for every photo a form takes (iOS PhotoField.swift): a pet, a person, a logo, a
//  place, a report. The current or new picture, choose / change, and remove with undo when the
//  saved one may go. Round for faces, rounded square otherwise.
//

/** `PhotoField.Shape`. */
object PhotoField {
    enum class Shape { circle, rounded }
}

/**
 * - `currentURL`: the saved photo's URL, if any.
 * - `allowsRemove`: whether the saved photo can be removed from here.
 * - `removeSavedTitle`: the button that removes the SAVED photo, when it should read
 *   differently from removing a new pick ("Use the business’s logo").
 */
@Composable
fun PhotoField(
    label: String,
    hint: String? = null,
    currentURL: String? = null,
    draft: MutableState<PhotoDraft>,
    shape: PhotoField.Shape = PhotoField.Shape.rounded,
    optional: Boolean = true,
    allowsRemove: Boolean = true,
    addTitle: String = L("Add a photo"),
    changeTitle: String = L("Change photo"),
    removeTitle: String = L("Remove"),
    removeSavedTitle: String? = null,
    removedNote: String = L("Removed when you save."),
    key: String? = null,
    form: FormState? = null,
    modifier: Modifier = Modifier,
) {
    // The picked item (iOS `PhotosPickerItem`): a new pick starts reading it.
    var item by remember { mutableStateOf<Uri?>(null) }
    var loading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) item = uri }

    val hasImage = draft.value.preview != null || (currentURL.nonEmpty != null && !draft.value.removed)

    LaunchedEffect(item) {
        val picked = item ?: return@LaunchedEffect
        loading = true
        try {
            val data = ImageUpload.load(picked)
            if (data != null) {
                val preview = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }
                draft.value = draft.value.copy(data = data, preview = preview, removed = false)
                if (key != null) form?.clear(key)
            } else {
                Toast.error(L("That photo could not be read. Try another one."))
            }
        } finally {
            loading = false
        }
    }

    FormField(label = label, key = key, hint = hint, form = form, optional = optional, modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Picture(shape, currentURL, draft.value, loading)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PMButton(
                    if (hasImage) changeTitle else addTitle,
                    systemImage = "photo.on.rectangle",
                    variant = ButtonVariant.outline,
                    size = ButtonSize.compact,
                ) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                when {
                    draft.value.preview != null -> Text(
                        removeTitle,
                        Modifier.plainButton {
                            draft.value = draft.value.copy(data = null, preview = null)
                            item = null
                        },
                        style = Theme.text(14, FontWeight.Bold),
                        color = Theme.coralText,
                    )
                    draft.value.removed -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(removedNote, style = Theme.captionFont, color = Theme.inkSoft)
                        Text(
                            L("Undo"),
                            Modifier.plainButton { draft.value = draft.value.copy(removed = false) },
                            style = Theme.text(14, FontWeight.Bold),
                            color = Theme.blueText,
                        )
                    }
                    allowsRemove && currentURL.nonEmpty != null -> Text(
                        removeSavedTitle ?: removeTitle,
                        Modifier.plainButton { draft.value = draft.value.copy(removed = true) },
                        style = Theme.text(14, FontWeight.Bold),
                        color = Theme.coralText,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Picture(shape: PhotoField.Shape, currentURL: String?, draft: PhotoDraft, loading: Boolean) {
    val side = if (shape == PhotoField.Shape.circle) 64.dp else 88.dp
    val outline = if (shape == PhotoField.Shape.circle) CircleShape else RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(side)
            .clip(outline)
            .background(Theme.surfaceMuted)
            .border(1.dp, Theme.borderSoft, outline),
        contentAlignment = Alignment.Center,
    ) {
        val preview = draft.preview
        val url = APIConfig.assetURL(currentURL)
        when {
            preview != null -> Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            !draft.removed && url != null -> SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                loading = {
                    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = Theme.inkSoft, strokeWidth = 2.dp) }
                },
            )
            else -> Glyph(if (shape == PhotoField.Shape.circle) "person.crop.circle" else "photo", 24.dp, tint = Theme.inkSoft)
        }
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
    }
}
