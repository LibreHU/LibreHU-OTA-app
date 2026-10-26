package org.librehu.ota.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.librehu.ota.R
import org.librehu.ota.data.Screenshots
import org.librehu.ota.data.Shot
import org.librehu.ota.ui.CarColors

/** Image from the network (Screenshots cache), a quiet placeholder until it is there. */
@Composable
fun RemoteImage(
    url: String,
    maxWidth: Int,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val context = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, url, maxWidth) {
        value = withContext(Dispatchers.IO) { Screenshots.bitmap(context, url, maxWidth) }
    }
    Box(modifier.background(CarColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** Row of screenshot thumbnails; a touch opens them full screen. Nothing when [shots] is empty. */
@Composable
fun ScreenshotStrip(
    shots: List<Shot>,
    height: Int = 120,
) {
    if (shots.isEmpty()) return
    var open by remember { mutableStateOf<Int?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(shots, key = { _, s -> s.url }) { i, s ->
            Column(Modifier.width((height * 16 / 9).dp)) {
                RemoteImage(
                    s.url,
                    480,
                    Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(12.dp)).clickable { open = i },
                    s.caption,
                )
                if (s.caption.isNotEmpty()) {
                    Text(s.caption, color = CarColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    open?.let { start -> ScreenshotViewer(shots, start) { open = null } }
}

/** Full screen screenshots, previous / next. */
@Composable
private fun ScreenshotViewer(
    shots: List<Shot>,
    start: Int,
    onClose: () -> Unit,
) {
    var i by remember { mutableStateOf(start) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = shots[i]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${i + 1} / ${shots.size}" + if (s.caption.isNotEmpty()) " · ${s.caption}" else "",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Pill(stringResource(R.string.previous), icon = Icons.AutoMirrored.Filled.ArrowBack, small = true, enabled = i > 0) { i-- }
                Pill(stringResource(R.string.next), icon = Icons.AutoMirrored.Filled.ArrowForward, small = true, enabled = i < shots.size - 1) { i++ }
                Pill(stringResource(R.string.close), icon = Icons.Default.Close, small = true, onClick = onClose)
            }
            RemoteImage(s.url, 1280, Modifier.fillMaxWidth().weight(1f).aspectRatio(16f / 9f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(12.dp)), s.caption)
        }
    }
}
