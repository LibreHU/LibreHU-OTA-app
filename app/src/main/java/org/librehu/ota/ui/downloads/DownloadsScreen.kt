package org.librehu.ota.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.History
import org.librehu.ota.data.Task
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.AppAction
import org.librehu.ota.ui.components.AppIcon
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.EmptyState
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Gap
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.SectionTitle
import org.librehu.ota.ui.components.TaskProgress
import org.librehu.ota.ui.components.TitleLine
import org.librehu.ota.ui.components.dateTime
import org.librehu.ota.ui.components.updateLine
import org.librehu.ota.ui.components.working

/** Downloads and installs running or paused, the ones finished or failed, then the history of what was installed. */
@Composable
fun DownloadsScreen(
    repo: AppRepository,
    show: (String) -> Unit,
) {
    val context = LocalContext.current
    val apps by repo.apps.collectAsStateWithLifecycle()
    val history by History.get(context).collectAsStateWithLifecycle()
    val running = apps.filter { it.working }
    val finished = apps.filter { !it.working && it.task != Task.Idle }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (running.isEmpty() && finished.isEmpty()) {
            item { EmptyState(Icons.Default.Download, stringResource(R.string.downloads_none), stringResource(R.string.downloads_none_hint)) }
        }
        if (running.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.downloads_running, running.size)) }
            items(running, key = { it.app.packageName }) { s -> TransferCard(s, repo, show) }
        }
        if (finished.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.downloads_finished)) }
            items(finished, key = { "done-" + it.app.packageName }) { s -> TransferCard(s, repo, show) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.set_history), Modifier.weight(1f))
                if (history.isNotEmpty()) Pill(stringResource(R.string.set_history_clear), small = true) { History.clear(context) }
            }
            if (history.isEmpty()) Hint(stringResource(R.string.set_history_empty))
        }
        items(history.take(100)) { e ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, color = CarColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Hint(dateTime(e.time))
                }
                Text("${e.detail} · ${e.result}", color = if (e.ok) CarColors.TextDim else ErrorColor, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun TransferCard(
    s: AppState,
    repo: AppRepository,
    show: (String) -> Unit,
) {
    Card(onClick = { show(s.app.packageName) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(s.app.packageName, s.installed, 44.dp)
            Gap()
            Column(Modifier.weight(1f)) { TitleLine(s.app.name, if (s.release != null) updateLine(s) else null) }
            if (s.working) {
                AppAction(s, repo, open = {}, small = true)
            } else {
                Pill(stringResource(R.string.dismiss), small = true) { repo.dismiss(s.app.packageName) }
            }
        }
        TaskProgress(s)
    }
}
