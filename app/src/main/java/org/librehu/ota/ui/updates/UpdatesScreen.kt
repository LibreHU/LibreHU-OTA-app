package org.librehu.ota.ui.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.ota.R
import org.librehu.ota.Transfers
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.Changes
import org.librehu.ota.data.OtaSettingsStore
import org.librehu.ota.data.Release
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.AppAction
import org.librehu.ota.ui.components.AppIcon
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.EmptyState
import org.librehu.ota.ui.components.Gap
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.SectionTitle
import org.librehu.ota.ui.components.TaskProgress
import org.librehu.ota.ui.components.TitleLine
import org.librehu.ota.ui.components.errorText
import org.librehu.ota.ui.components.isoDate
import org.librehu.ota.ui.components.updateLine
import org.librehu.ota.ui.components.working

/** Apps with a newer build: update one or all, what changes; the ignored ones apart. */
@Composable
fun UpdatesScreen(
    repo: AppRepository,
    open: (String) -> Unit,
    show: (String) -> Unit,
) {
    val apps by repo.apps.collectAsStateWithLifecycle()
    val busy by repo.busy.collectAsStateWithLifecycle()
    val settings by OtaSettingsStore.get(LocalContext.current).collectAsStateWithLifecycle()
    // Kept in the list while they install, so that the progress stays where the button was.
    val pending = apps.filter { (it.updateAvailable || it.working) && it.installed && it.app.packageName !in settings.ignored }
    val ignored = apps.filter { it.updateAvailable && it.app.packageName in settings.ignored }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (pending.isEmpty()) {
            item {
                EmptyState(
                    Icons.Default.DoneAll,
                    stringResource(R.string.updates_none),
                    stringResource(if (busy) R.string.checking else R.string.updates_none_hint),
                )
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.updates_title, pending.size, pending.size),
                        color = CarColors.Text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(stringResource(R.string.update_all), selected = true, enabled = pending.any { !it.working }) { repo.updateAll() }
                }
            }
            items(pending, key = { it.app.packageName }) { s -> UpdateItem(s, repo, open, show) }
        }
        if (ignored.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.updates_ignored, ignored.size)) }
            items(ignored, key = { "ignored-" + it.app.packageName }) { s ->
                Card(onClick = { show(s.app.packageName) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(s.app.packageName, s.installed, 44.dp)
                        Gap()
                        TitleLine(s.app.name, updateLine(s), Modifier.weight(1f))
                        Pill(stringResource(R.string.unignore), small = true) { repo.setIgnored(s.app.packageName, false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateItem(
    s: AppState,
    repo: AppRepository,
    open: (String) -> Unit,
    show: (String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(s.app.packageName) { mutableStateOf(false) }
    val r = s.release
    Card(onClick = { show(s.app.packageName) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(s.app.packageName, s.installed)
            Gap()
            Column(Modifier.weight(1f)) {
                TitleLine(
                    s.app.name,
                    listOfNotNull(
                        updateLine(s),
                        r?.size?.takeIf { it > 0 }?.let { Transfers.size(context, it) },
                        r?.publishedAt?.let { isoDate(it) }?.takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                )
            }
            if (r != null && !s.working) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        stringResource(R.string.details_whats_new),
                        tint = CarColors.TextDim,
                    )
                }
            }
            AppAction(s, repo, open, small = true)
        }
        if (s.working) TaskProgress(s)
        if (expanded && r != null) {
            if (r.summary.isNotEmpty()) Hint(r.summary)
            ChangesList(s, repo, r)
        }
    }
}

/** Commits between the installed version and [r]: the ones it adds, or the ones going back to it removes. */
@Composable
fun ChangesList(
    s: AppState,
    repo: AppRepository,
    r: Release,
) {
    val context = LocalContext.current
    var changes by remember(r.tag) { mutableStateOf<Result<Changes?>?>(null) }
    LaunchedEffect(r.tag) { changes = runCatching { repo.changes(s.app.packageName, r) } }
    val c = changes
    when {
        !s.installed || r.versionCode == s.installedCode -> {}
        c == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
        c.isFailure -> Hint(errorText(context, c.exceptionOrNull()))
        c.getOrNull() == null -> Hint(stringResource(R.string.changes_unknown))
        else -> {
            val ch = c.getOrNull()!!
            val lines = ch.added.ifEmpty { ch.removed }
            Text(
                stringResource(if (ch.added.isNotEmpty()) R.string.changes_added else R.string.changes_removed, lines.size),
                color = CarColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Column(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (line in lines.take(40)) Text("• $line", color = CarColors.Text, fontSize = 14.sp)
                if (lines.size > 40) Hint("…")
            }
        }
    }
}
