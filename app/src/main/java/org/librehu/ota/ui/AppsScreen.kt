package org.librehu.ota.ui

import android.content.Context
import android.graphics.drawable.Drawable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.librehu.ota.CheckJob
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.Edition
import org.librehu.ota.data.Task
import org.librehu.ota.install.Root
import java.text.DateFormat
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
        items(apps, key = { it.app.packageName }) { s -> AppCard(s, repo, open) }
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
            Actions(s, repo, open)
        }
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
    open: (String) -> Unit,
) {
    val working = s.task is Task.Downloading || s.task == Task.Installing
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            working -> {}
            s.updateAvailable -> Pill(stringResource(R.string.update), selected = true) { repo.install(s.app.packageName) }
            s.canInstall -> Pill(stringResource(R.string.install), selected = true) { repo.install(s.app.packageName) }
            s.installed && s.release != null -> Pill(stringResource(R.string.reinstall)) { repo.install(s.app.packageName) }
        }
        if (s.installed && !working) Pill(stringResource(R.string.open)) { open(s.app.packageName) }
    }
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
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) CarColors.Accent else CarColors.SurfaceHigh)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
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
        Text(label, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
