package org.librehu.ota.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.Edition
import org.librehu.ota.ui.components.AppAction
import org.librehu.ota.ui.components.AppIcon
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.EmptyState
import org.librehu.ota.ui.components.Gap
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.SectionTitle
import org.librehu.ota.ui.components.StatusChip
import org.librehu.ota.ui.components.TaskProgress
import org.librehu.ota.ui.components.TitleLine
import org.librehu.ota.ui.components.installedLine
import org.librehu.ota.ui.components.statusLabel
import org.librehu.ota.ui.components.working

/** The catalogue as tiles: installed apps first, then the ones to install; a tile opens the app's details. */
@Composable
fun AppsScreen(
    repo: AppRepository,
    open: (String) -> Unit,
    show: (String) -> Unit,
) {
    val apps by repo.apps.collectAsStateWithLifecycle()
    val edition by repo.editionFlow.collectAsStateWithLifecycle()
    val installed = apps.filter { it.installed }
    val available = apps.filter { !it.installed }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header {
            Hint(
                stringResource(
                    R.string.edition_current,
                    stringResource(
                        when (edition) {
                            Edition.LIBREHU_SERVICE -> R.string.edition_service
                            Edition.IVI -> R.string.edition_ivi
                            Edition.GENERIC -> R.string.edition_generic
                        },
                    ),
                ),
            )
        }
        if (apps.isEmpty()) header { EmptyState(Icons.Default.CloudOff, stringResource(R.string.apps_empty)) }
        if (installed.isNotEmpty()) {
            header { SectionTitle(stringResource(R.string.apps_installed, installed.size), Modifier.padding(top = 4.dp)) }
            items(installed, key = { it.app.packageName }) { s -> AppTile(s, repo, open, show) }
        }
        if (available.isNotEmpty()) {
            header { SectionTitle(stringResource(R.string.apps_available, available.size), Modifier.padding(top = 12.dp)) }
            items(available, key = { it.app.packageName }) { s -> AppTile(s, repo, open, show) }
        }
    }
}

private fun LazyGridScope.header(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun AppTile(
    s: AppState,
    repo: AppRepository,
    open: (String) -> Unit,
    show: (String) -> Unit,
) {
    Card(onClick = { show(s.app.packageName) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(s.app.packageName, s.installed)
            Gap()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TitleLine(s.app.name, if (s.installed) installedLine(s) else s.app.description.ifEmpty { s.app.repo })
                val (label, accent) = statusLabel(s)
                StatusChip(label, accent)
            }
            AppAction(s, repo, open, small = true)
        }
        if (s.working) TaskProgress(s)
    }
}
