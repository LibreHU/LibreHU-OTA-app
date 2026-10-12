package org.librehu.ota.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.ota.CheckJob
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.Changes
import org.librehu.ota.data.Edition
import org.librehu.ota.data.RateLimitException
import org.librehu.ota.data.Release
import org.librehu.ota.data.Task
import org.librehu.ota.install.Root
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/** One card per LibreHU app: installed / available version, install or update; edition and checks on top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppsScreen(
    repo: AppRepository,
    open: (String) -> Unit,
) {
    val apps by repo.apps.collectAsStateWithLifecycle()
    val busy by repo.busy.collectAsStateWithLifecycle()
    val message by repo.message.collectAsStateWithLifecycle()
    val edition by repo.editionFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var root by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { root = withContext(Dispatchers.IO) { Root.isAvailable() } }
    var auto by remember { mutableStateOf(CheckJob.enabled(context)) }
    val updates = apps.count { it.updateAvailable }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CarColors.Background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), color = CarColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                    Text(status(context, busy, message, repo.lastCheck, updates), color = CarColors.TextDim, fontSize = 15.sp)
                }
                if (updates > 0 && !busy) {
                    Pill(stringResource(R.string.update_all), selected = true) {
                        apps.filter { it.updateAvailable }.forEach { repo.install(it.app.packageName) }
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Pill(stringResource(R.string.check), icon = Icons.Default.Refresh, enabled = !busy) { repo.check() }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = CarColors.Accent)
        }
        item {
            Card {
                Text(stringResource(R.string.edition), color = CarColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (
                    (e, label) in
                    listOf(
                        Edition.LIBREHU_SERVICE to R.string.edition_service,
                        Edition.IVI to R.string.edition_ivi,
                        Edition.GENERIC to R.string.edition_generic,
                    )
                    ) {
                        Pill(stringResource(label), selected = e == edition) { repo.setEdition(e) }
                    }
                }
                Text(stringResource(R.string.edition_hint), color = CarColors.TextDim, fontSize = 14.sp)
                Text(
                    stringResource(
                        when (root) {
                            null -> R.string.root_checking
                            true -> R.string.root_yes
                            false -> R.string.root_no
                        },
                    ),
                    color = CarColors.TextDim,
                    fontSize = 14.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auto_check), color = CarColors.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = auto,
                        onCheckedChange = {
                            auto = it
                            CheckJob.setEnabled(context, it)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = CarColors.Accent, checkedThumbColor = CarColors.OnAccent),
                    )
                }
            }
        }
        items(apps, key = { it.app.packageName }) { s -> AppCard(s, repo, root == true, open) }
    }
}

private fun status(
    context: Context,
    busy: Boolean,
    message: String?,
    last: Long,
    updates: Int,
): String =
    when {
        busy -> {
            context.getString(R.string.checking)
        }

        message != null && message.startsWith("rate:") -> {
            val reset = message.removePrefix("rate:").toLongOrNull() ?: 0
            context.getString(R.string.rate_limited, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reset * 1000)))
        }

        message != null -> {
            context.getString(R.string.check_failed, message)
        }

        last == 0L -> {
            context.getString(R.string.never_checked)
        }

        else -> {
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
            if (updates > 0) {
                context.resources.getQuantityString(R.plurals.updates_title, updates, updates) + " · " + time
            } else {
                context.getString(R.string.all_up_to_date, time)
            }
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppCard(
    s: AppState,
    repo: AppRepository,
    root: Boolean,
    open: (String) -> Unit,
) {
    val context = LocalContext.current
    val icon =
        remember(s.app.packageName, s.installedCode) {
            if (s.installed) runCatching { context.packageManager.getApplicationIcon(s.app.packageName) }.getOrNull() else null
        }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.app.name, color = CarColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(versionLine(s), color = CarColors.TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                s.release?.summary?.takeIf { it.isNotEmpty() && s.updateAvailable }?.let {
                    Text(it, color = CarColors.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            Actions(s, repo)
        }
        var versions by remember { mutableStateOf(false) }
        var logs by remember { mutableStateOf(false) }
        val working = s.task is Task.Downloading || s.task == Task.Installing
        if (!working) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.installed) Pill(stringResource(R.string.open), small = true) { open(s.app.packageName) }
                Pill(stringResource(R.string.versions), small = true, icon = Icons.Default.History) { versions = true }
                if (s.installed) Pill(stringResource(R.string.logs), small = true, icon = Icons.AutoMirrored.Filled.Article) { logs = true }
                if (root && s.release != null) {
                    Pill(stringResource(R.string.system_install), small = true, icon = Icons.Default.PhonelinkSetup) {
                        repo.prepareSystem(s.app.packageName)
                    }
                }
            }
        }
        if (versions) VersionsDialog(s, repo, root) { versions = false }
        if (logs) LogsDialog(s, repo) { logs = false }
        when (val t = s.task) {
            is Task.Downloading -> {
                if (t.progress >= 0) {
                    LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth(), color = CarColors.Accent)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
                }
            }

            Task.Installing -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
            }

            is Task.Failed -> {
                Text(stringResource(R.string.install_failed, t.message), color = Color(0xFFF28B82), fontSize = 14.sp)
            }

            Task.Done -> {
                Text(stringResource(R.string.installed_ok), color = CarColors.TextDim, fontSize = 14.sp)
            }

            Task.RebootNeeded -> {
                Text(stringResource(R.string.reboot_needed), color = CarColors.Accent, fontSize = 14.sp)
            }

            is Task.Incompatible -> {
                IncompatibleDialog(s, repo)
            }

            is Task.Downgrade -> {
                DowngradeDialog(s, repo)
            }

            is Task.SystemChoice -> {
                SystemDialog(s, t, repo)
            }

            Task.Idle -> {}
        }
    }
}

private fun versionLine(s: AppState): String {
    val installed = s.installedName?.let { "$it (${s.installedCode})" }
    val available = s.release?.let { "${it.versionName} (${it.versionCode})" }
    return listOfNotNull(
        installed?.let { "↧ $it" },
        available?.let { "☁ $it" },
        s.checkError?.takeIf { s.release == null },
    ).joinToString("   ").ifEmpty { "—" }
}

@Composable
private fun Actions(
    s: AppState,
    repo: AppRepository,
) {
    val working = s.task is Task.Downloading || s.task == Task.Installing
    when {
        working -> {}
        s.updateAvailable -> Pill(stringResource(R.string.update), selected = true) { repo.install(s.app.packageName) }
        s.canInstall -> Pill(stringResource(R.string.install), selected = true) { repo.install(s.app.packageName) }
        s.installed && s.release != null -> Pill(stringResource(R.string.reinstall)) { repo.install(s.app.packageName) }
    }
}

/** Builds kept on GitHub (the last 15 of the edition's branch): pick one to update or go back to it. */
@Composable
private fun VersionsDialog(
    s: AppState,
    repo: AppRepository,
    root: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var list by remember { mutableStateOf<List<Release>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(s.app.packageName) {
        runCatching { repo.versions(s.app.packageName) }
            .onSuccess { list = it }
            .onFailure { error = errorText(context, it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.versions_title, s.app.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val l = list
                when {
                    error != null -> Text(error.orEmpty(), color = Color(0xFFF28B82))
                    l == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
                    l.isEmpty() -> Text(stringResource(R.string.versions_none))
                    else ->
                        LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(l, key = { it.tag }) { r -> VersionRow(r, s, r == selected) { selected = r } }
                        }
                }
                selected?.let { ChangesBlock(s, repo, it) }
                Text(stringResource(R.string.versions_hint), color = CarColors.TextDim, fontSize = 13.sp)
            }
        },
        confirmButton = {
            val r = selected
            if (r != null && r.versionCode != s.installedCode) {
                val older = s.installedCode != null && r.versionCode < s.installedCode
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        repo.install(s.app.packageName, r)
                        onDismiss()
                    }) { Text(stringResource(if (older) R.string.install_older else R.string.install_this)) }
                    if (root) {
                        TextButton(onClick = {
                            repo.prepareSystem(s.app.packageName, r)
                            onDismiss()
                        }) { Text(stringResource(R.string.system_install)) }
                    }
                }
            } else if (r != null && root) {
                TextButton(onClick = {
                    repo.prepareSystem(s.app.packageName, r)
                    onDismiss()
                }) { Text(stringResource(R.string.system_install)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun VersionRow(
    r: Release,
    s: AppState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val installed = r.versionCode == s.installedCode
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) CarColors.Accent else CarColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        val color = if (selected) CarColors.OnAccent else CarColors.Text
        val tags =
            listOfNotNull(
                stringResource(R.string.version_installed).takeIf { installed },
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
            listOf(date(r.publishedAt), r.summary).filter { it.isNotEmpty() }.joinToString(" · "),
            color = if (selected) CarColors.OnAccent else CarColors.TextDim,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What changes from the installed version to [r]: the commits it adds, or the ones going back removes. */
@Composable
private fun ChangesBlock(
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
        c.isFailure -> Text(errorText(context, c.exceptionOrNull()), color = CarColors.TextDim, fontSize = 13.sp)
        c.getOrNull() == null -> Text(stringResource(R.string.changes_unknown), color = CarColors.TextDim, fontSize = 13.sp)
        else -> {
            val ch = c.getOrNull()!!
            val lines = ch.added.ifEmpty { ch.removed }
            Text(
                stringResource(if (ch.added.isNotEmpty()) R.string.changes_added else R.string.changes_removed, lines.size),
                color = CarColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            LazyColumn(Modifier.heightIn(max = 120.dp)) {
                items(lines) { Text("• $it", color = CarColors.Text, fontSize = 13.sp) }
            }
        }
    }
}

/** Log lines of the app, newest at the bottom; refresh and export to a file. */
@Composable
private fun LogsDialog(
    s: AppState,
    repo: AppRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var denied by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        lines = null
        val text = repo.logs(s.app.packageName)
        denied = text == null
        lines = text?.lines()?.filter { it.isNotEmpty() }.orEmpty()
    }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val text = lines?.joinToString("\n") ?: return@rememberLauncherForActivityResult
            if (uri != null) {
                scope.launch(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                }
            }
        }
    val state = rememberLazyListState()
    LaunchedEffect(lines) { lines?.let { if (it.isNotEmpty()) state.scrollToItem(it.lastIndex) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.logs_title, s.app.name)) },
        text = {
            val l = lines
            when {
                l == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
                denied -> Text(stringResource(R.string.logs_denied))
                l.isEmpty() -> Text(stringResource(R.string.logs_empty))
                else ->
                    SelectionContainer {
                        LazyColumn(Modifier.heightIn(max = 400.dp), state = state) {
                            items(l) { line -> Text(line, color = logColor(line), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { round++ }) { Text(stringResource(R.string.refresh)) }
                if (!lines.isNullOrEmpty()) {
                    TextButton(onClick = { export.launch("${s.app.packageName}-${System.currentTimeMillis() / 1000}.log") }) {
                        Text(stringResource(R.string.export))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Error and fatal lines red, warnings amber (the priority letter of `threadtime`). */
@Composable
private fun logColor(line: String): Color =
    when (line.split(' ').filter { it.isNotEmpty() }.getOrNull(5)) {
        "E", "F" -> Color(0xFFF28B82)
        "W" -> Color(0xFFFDD663)
        "D", "V" -> CarColors.TextDim
        else -> CarColors.Text
    }

private fun errorText(
    context: Context,
    e: Throwable?,
): String =
    if (e is RateLimitException) {
        context.getString(R.string.rate_limited, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.resetEpochS * 1000)))
    } else {
        context.getString(R.string.check_failed, e?.message ?: "?")
    }

private fun date(iso: String): String =
    runCatching { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date.from(Instant.parse(iso))) }
        .getOrDefault("")

/** Where to put the APK in /system: priv-app (privileged permissions, whitelisted) or app; the best one first. */
@Composable
private fun SystemDialog(
    s: AppState,
    t: Task.SystemChoice,
    repo: AppRepository,
) {
    val plan = t.plan
    AlertDialog(
        onDismissRequest = { repo.dismiss(s.app.packageName) },
        title = { Text(stringResource(R.string.system_title, s.app.name)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.system_text))
                plan.current?.let { Text(stringResource(R.string.system_current, it), color = CarColors.TextDim, fontSize = 14.sp) }
                if (plan.privileged.isEmpty()) {
                    Text(stringResource(R.string.system_no_privileged))
                } else {
                    Text(stringResource(R.string.system_privileged, plan.privileged.size))
                    Text(
                        plan.privileged.joinToString("\n") { "• " + it.substringAfterLast('.') },
                        color = CarColors.TextDim,
                        fontSize = 13.sp,
                    )
                }
                if (plan.otherSignature) Text(stringResource(R.string.system_other_signature), color = Color(0xFFF28B82))
                Text(stringResource(R.string.system_warning), color = CarColors.TextDim, fontSize = 13.sp)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                val priv = stringResource(R.string.system_priv_app)
                val app = stringResource(R.string.system_app)
                val best = stringResource(R.string.recommended)
                for (privApp in if (plan.recommendPrivApp) listOf(true, false) else listOf(false, true)) {
                    TextButton(onClick = { repo.installSystem(s.app.packageName, privApp) }) {
                        Text((if (privApp) priv else app) + if (privApp == plan.recommendPrivApp) " · $best" else "")
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { repo.dismiss(s.app.packageName) }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Older build than the installed one: Android needs it uninstalled first, or as root with the data kept. */
@Composable
private fun DowngradeDialog(
    s: AppState,
    repo: AppRepository,
) {
    var root by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { root = withContext(Dispatchers.IO) { Root.isAvailable() } }
    AlertDialog(
        onDismissRequest = { repo.dismiss(s.app.packageName) },
        title = { Text(stringResource(R.string.downgrade_title, s.app.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.downgrade_text))
                if (root) Text(stringResource(R.string.downgrade_keep_text))
                if (s.systemApp) Text(stringResource(R.string.downgrade_system))
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { repo.reinstall(s.app.packageName) }) { Text(stringResource(R.string.uninstall_install)) }
                if (root) {
                    TextButton(onClick = { repo.reinstall(s.app.packageName, keepData = true) }) {
                        Text(stringResource(R.string.downgrade_keep))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { repo.dismiss(s.app.packageName) }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun IncompatibleDialog(
    s: AppState,
    repo: AppRepository,
) {
    AlertDialog(
        onDismissRequest = { repo.dismiss(s.app.packageName) },
        title = { Text(stringResource(R.string.incompatible_title, s.app.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.incompatible_text))
                if (s.systemApp) Text(stringResource(R.string.incompatible_system))
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { repo.reinstall(s.app.packageName) }) { Text(stringResource(R.string.uninstall_install)) }
                if (s.systemApp) {
                    TextButton(onClick = { repo.replaceSystem(s.app.packageName) }) { Text(stringResource(R.string.replace_system)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = { repo.dismiss(s.app.packageName) }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun AppIcon(icon: Drawable?) {
    val bitmap = remember(icon) { icon?.toBitmap(96, 96)?.asImageBitmap() }
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(CarColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, null, modifier = Modifier.size(48.dp))
        } else {
            Icon(Icons.Default.Apps, null, tint = CarColors.TextDim, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(CarColors.Surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Pill(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .heightIn(min = if (small) 40.dp else 48.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) CarColors.Accent else CarColors.SurfaceHigh)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = if (small) 16.dp else 20.dp, vertical = if (small) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color =
            if (selected) {
                CarColors.OnAccent
            } else if (enabled) {
                CarColors.Text
            } else {
                CarColors.TextDim
            }
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = color, fontSize = if (small) 15.sp else 17.sp, fontWeight = FontWeight.Medium)
    }
}
