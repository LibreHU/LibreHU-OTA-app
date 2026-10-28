package org.librehu.ota.ui.details

import org.librehu.ota.ui.carShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.ota.R
import org.librehu.ota.Transfers
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.OtaSettingsStore
import org.librehu.ota.data.Release
import org.librehu.ota.data.Screenshots
import org.librehu.ota.data.Shot
import org.librehu.ota.install.Root
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.AppAction
import org.librehu.ota.ui.components.AppIcon
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.InfoRow
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.ScreenshotStrip
import org.librehu.ota.ui.components.SectionTitle
import org.librehu.ota.ui.components.StatusChip
import org.librehu.ota.ui.components.TaskProgress
import org.librehu.ota.ui.components.errorText
import org.librehu.ota.ui.components.isoDate
import org.librehu.ota.ui.components.statusLabel
import org.librehu.ota.ui.components.working
import org.librehu.ota.ui.updates.ChangesList

/**
 * One app: what is installed where, its screenshots (gallery of the LibreHU site), what GitHub has, every build kept,
 * its log, and the /system install.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDetailsScreen(
    pkg: String,
    repo: AppRepository,
    open: (String) -> Unit,
) {
    val apps by repo.apps.collectAsStateWithLifecycle()
    val edition by repo.editionFlow.collectAsStateWithLifecycle()
    val settings by OtaSettingsStore.get(LocalContext.current).collectAsStateWithLifecycle()
    val s = apps.firstOrNull { it.app.packageName == pkg } ?: return
    val context = LocalContext.current
    var root by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { root = withContext(Dispatchers.IO) { Root.isAvailable() } }
    var logs by remember { mutableStateOf(false) }
    var uninstall by remember { mutableStateOf(false) }
    var shots by remember(s.app.id) { mutableStateOf<List<Shot>>(emptyList()) }
    LaunchedEffect(s.app.id) { shots = withContext(Dispatchers.IO) { Screenshots.forApp(s.app.id) } }
    val ignored = pkg in settings.ignored

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, s.installed, 88.dp)
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.app.name, color = CarColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hint("$pkg · github.com/${s.app.repo}")
                        val (label, accent) = statusLabel(s)
                        StatusChip(label, accent)
                    }
                    AppAction(s, repo, open)
                }
                if (s.app.description.isNotEmpty()) Text(s.app.description, color = CarColors.Text, fontSize = 16.sp)
                if (s.working || s.task != org.librehu.ota.data.Task.Idle) TaskProgress(s)
                if (!s.working) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (s.installed && (s.updateAvailable || s.canInstall)) Pill(stringResource(R.string.open), small = true) { open(pkg) }
                        if (s.installed && s.release != null && !s.updateAvailable) {
                            Pill(stringResource(R.string.reinstall), small = true) { repo.install(pkg) }
                        }
                        if (s.installed) Pill(stringResource(R.string.logs), small = true, icon = Icons.AutoMirrored.Filled.Article) { logs = true }
                        if (root && s.release != null) {
                            Pill(stringResource(R.string.system_install), small = true, icon = Icons.Default.PhonelinkSetup) { repo.prepareSystem(pkg) }
                        }
                        if (s.installed) {
                            Pill(stringResource(if (ignored) R.string.unignore else R.string.ignore), selected = ignored, small = true, icon = Icons.Default.NotificationsOff) {
                                repo.setIgnored(pkg, !ignored)
                            }
                        }
                        if (s.dataCode != null || s.systemCode != null) {
                            Pill(stringResource(R.string.uninstall), small = true, icon = Icons.Default.Delete) { uninstall = true }
                        }
                        if (s.task != org.librehu.ota.data.Task.Idle) Pill(stringResource(R.string.dismiss), small = true) { repo.dismiss(pkg) }
                    }
                }
            }
        }
        if (shots.isNotEmpty()) {
            item {
                Card {
                    SectionTitle(stringResource(R.string.details_screenshots))
                    ScreenshotStrip(shots)
                }
            }
        }
        item {
            Card {
                SectionTitle(stringResource(R.string.details_info))
                val none = stringResource(R.string.not_installed_short)
                InfoRow("/data", s.dataName?.let { "$it (${s.dataCode})" } ?: none)
                InfoRow("/system", s.systemName?.let { "$it (${s.systemCode})" } ?: none)
                InfoRow(stringResource(R.string.details_available), s.release?.let { "${it.versionName} (${it.versionCode})" } ?: (s.checkError ?: "—"))
                InfoRow(stringResource(R.string.details_branch), s.app.branchFor(edition))
                s.release?.let { r ->
                    isoDate(r.publishedAt).takeIf { it.isNotEmpty() }?.let { InfoRow(stringResource(R.string.details_published), it) }
                    if (r.size > 0) InfoRow(stringResource(R.string.details_size), Transfers.size(context, r.size))
                }
            }
        }
        s.release?.let { r ->
            if (s.updateAvailable || r.summary.isNotEmpty()) {
                item {
                    Card {
                        SectionTitle(stringResource(R.string.details_whats_new))
                        if (r.summary.isNotEmpty()) Text(r.summary, color = CarColors.Text, fontSize = 15.sp)
                        ChangesList(s, repo, r)
                    }
                }
            }
        }
        item { VersionsCard(s, repo, root) }
    }
    if (logs) LogsDialog(s, repo) { logs = false }
    if (uninstall) UninstallDialog(s, repo, root) { uninstall = false }
}

/**
 * Builds kept on GitHub for the edition's branch (asked on demand: one GitHub request): pick one to update to, go
 * back to, or put in /system.
 */
@Composable
private fun VersionsCard(
    s: AppState,
    repo: AppRepository,
    root: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember(s.app.packageName) { mutableStateOf<List<Release>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Release?>(null) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.versions), Modifier.weight(1f))
            if (list == null && !loading) {
                Pill(stringResource(R.string.details_load_versions), small = true, icon = Icons.Default.History) {
                    loading = true
                    scope.launch {
                        runCatching { repo.versions(s.app.packageName) }
                            .onSuccess { list = it }
                            .onFailure { error = errorText(context, it) }
                        loading = false
                    }
                }
            }
        }
        Hint(stringResource(R.string.versions_hint))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
        error?.let { Text(it, color = ErrorColor, fontSize = 14.sp) }
        val l = list
        if (l != null && l.isEmpty()) Hint(stringResource(R.string.versions_none))
        for (r in l.orEmpty()) {
            VersionRow(r, s, r == selected) { selected = if (selected == r) null else r }
            if (r == selected) {
                ChangesList(s, repo, r)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (r.versionCode != s.installedCode) {
                        val older = s.installedCode != null && r.versionCode < s.installedCode
                        Pill(stringResource(if (older) R.string.install_older else R.string.install_this), selected = true, small = true) {
                            repo.install(s.app.packageName, r)
                        }
                    }
                    if (root) Pill(stringResource(R.string.system_install), small = true) { repo.prepareSystem(s.app.packageName, r) }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    r: Release,
    s: AppState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(carShape(16.dp))
            .background(if (selected) CarColors.Accent else CarColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val color = if (selected) CarColors.OnAccent else CarColors.Text
        val tags =
            listOfNotNull(
                stringResource(R.string.version_installed).takeIf { r.versionCode == s.installedCode },
                stringResource(R.string.version_latest).takeIf { r.tag == s.release?.tag || r.versionCode == s.release?.versionCode },
            )
        Text(
            "${r.versionName} (${r.versionCode})" + tags.joinToString("") { " · $it" },
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(isoDate(r.publishedAt), r.summary).filter { it.isNotEmpty() }.joinToString(" · "),
            color = if (selected) CarColors.OnAccent else CarColors.TextDim,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
