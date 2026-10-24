package org.librehu.ota.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.ota.R
import org.librehu.ota.Transfers
import org.librehu.ota.data.Configs
import org.librehu.ota.data.Offline
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.SectionTitle

/** Install from a USB key, offline ([Offline]): the folder picked, its files checked one by one, then installed. */
@Composable
fun OfflinePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Offline.Item>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val checked = remember { mutableStateMapOf<Uri, Offline.Checked>() }
    val results = remember { mutableStateMapOf<Uri, Pair<String, Boolean>>() }
    var askReboot by remember { mutableStateOf(false) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null) return@rememberLauncherForActivityResult
            busy = true
            checked.clear()
            results.clear()
            scope.launch {
                items = withContext(Dispatchers.IO) { Offline.list(context, tree) }
                busy = false
            }
        }

    Card {
        SectionTitle(stringResource(R.string.offline_title))
        Hint(stringResource(R.string.offline_hint))
        Pill(stringResource(R.string.offline_pick), selected = items == null, small = true, enabled = !busy) { runCatching { pick.launch(null) } }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
        items?.let { list ->
            if (list.isEmpty()) Hint(stringResource(R.string.offline_empty))
            for (item in list) {
                val c = checked[item.uri]
                val r = results[item.uri]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${item.name} · ${Transfers.size(context, item.size)}",
                        color = CarColors.Text,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        r != null -> {
                            Unit
                        }

                        c == null -> {
                            Pill(stringResource(R.string.offline_check), small = true, enabled = !busy) {
                                busy = true
                                scope.launch {
                                    checked[item.uri] = withContext(Dispatchers.IO) { Offline.check(context, item) }
                                    busy = false
                                }
                            }
                        }

                        c.ok -> {
                            Pill(stringResource(R.string.offline_install), selected = true, small = true, enabled = !busy) {
                                busy = true
                                scope.launch {
                                    val out = withContext(Dispatchers.IO) { Offline.install(context, c) }
                                    results[item.uri] = out
                                    if (out.second && item.kind == Offline.Kind.ZIP) askReboot = true
                                    busy = false
                                }
                            }
                        }
                    }
                }
                when {
                    r != null -> Text(r.first, color = if (r.second) CarColors.Accent else ErrorColor, fontSize = 14.sp)
                    c != null -> Text(c.detail, color = if (c.ok) CarColors.TextDim else ErrorColor, fontSize = 14.sp)
                }
            }
        }
        if (askReboot) {
            Pill(stringResource(R.string.offline_reboot_recovery), small = true) {
                scope.launch(Dispatchers.IO) { Configs.rebootRecovery() }
            }
        }
    }
}
