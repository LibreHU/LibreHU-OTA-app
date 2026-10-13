package org.librehu.ota.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.librehu.ota.R
import org.librehu.ota.Transfers
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.Task
import org.librehu.ota.ui.CarColors

/** A download or an install runs (or waits / is paused): no other action meanwhile. */
val AppState.working: Boolean
    get() = task is Task.Downloading || task == Task.Installing || task == Task.Queued || task is Task.Paused

/** A choice is waiting (signature, older version, /system): shown as a dialog wherever the user is. */
val AppState.needsChoice: Boolean
    get() = task is Task.Incompatible || task is Task.Downgrade || task is Task.SystemChoice

/** Installed and ignored by "update all". */
fun AppState.ignored(ignored: Set<String>) = app.packageName in ignored

/** Short state of an app for the lists. */
@Composable
fun statusLabel(s: AppState): Pair<String, Boolean> =
    when {
        s.task is Task.Downloading || s.task == Task.Queued -> stringResource(R.string.status_downloading) to true
        s.task == Task.Installing -> stringResource(R.string.tr_installing) to true
        s.task is Task.Paused -> stringResource(R.string.status_paused) to false
        s.task == Task.RebootNeeded -> stringResource(R.string.status_reboot) to true
        s.task is Task.Failed -> stringResource(R.string.status_failed) to false
        s.updateAvailable -> stringResource(R.string.status_update) to true
        !s.installed -> stringResource(R.string.status_not_installed) to false
        s.release == null -> stringResource(R.string.status_unknown) to false
        else -> stringResource(R.string.status_up_to_date) to false
    }

/** "1.2 (12)" of the installed version, or "not installed". */
@Composable
fun installedLine(s: AppState): String = s.installedName?.let { "$it (${s.installedCode})" } ?: stringResource(R.string.not_installed_short)

/** "1.2 → 1.3" when an update is there. */
fun updateLine(s: AppState): String = "${s.installedName ?: "—"}  →  ${s.release?.versionName ?: "?"}"

/**
 * Main action of an app: install / update / open, and while it works its progress (ring with the percentage) with
 * pause / resume and cancel.
 */
@Composable
fun AppAction(
    s: AppState,
    repo: AppRepository,
    open: (String) -> Unit,
    small: Boolean = false,
) {
    val pkg = s.app.packageName
    when (val t = s.task) {
        is Task.Downloading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(t.progress)
                IconButton(onClick = { repo.pause(pkg) }) { Icon(Icons.Default.Pause, stringResource(R.string.tr_pause), tint = CarColors.Text) }
                IconButton(onClick = { repo.cancel(pkg) }) {
                    Icon(Icons.Default.Close, stringResource(android.R.string.cancel), tint = CarColors.Text)
                }
            }
        }

        is Task.Paused -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(t.progress, paused = true)
                IconButton(onClick = { repo.resume(pkg) }) {
                    Icon(Icons.Default.PlayArrow, stringResource(R.string.tr_resume), tint = CarColors.Accent)
                }
                IconButton(onClick = { repo.cancel(pkg) }) {
                    Icon(Icons.Default.Close, stringResource(android.R.string.cancel), tint = CarColors.Text)
                }
            }
        }

        Task.Queued, Task.Installing -> Ring(-1f)

        else ->
            when {
                s.updateAvailable -> Pill(stringResource(R.string.update), selected = true, small = small) { repo.install(pkg) }
                s.canInstall -> Pill(stringResource(R.string.install), selected = true, small = small) { repo.install(pkg) }
                s.installed -> Pill(stringResource(R.string.open), small = small) { open(pkg) }
            }
    }
}

/** Circular progress, the percentage inside; indeterminate when [progress] < 0. */
@Composable
fun Ring(
    progress: Float,
    paused: Boolean = false,
) {
    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
        val color = if (paused) CarColors.TextDim else CarColors.Accent
        if (progress >= 0) {
            CircularProgressIndicator(progress = { progress }, color = color, trackColor = CarColors.SurfaceHigh, modifier = Modifier.size(44.dp))
            Text("${(progress * 100).toInt()}", color = CarColors.Text, fontSize = 12.sp)
        } else {
            CircularProgressIndicator(color = color, trackColor = CarColors.SurfaceHigh, modifier = Modifier.size(44.dp))
        }
    }
}

/** Progress of a running task as a bar and its numbers (download speed, time left), for the cards. */
@Composable
fun TaskProgress(s: AppState) {
    val context = LocalContext.current
    when (val t = s.task) {
        is Task.Downloading ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t.progress >= 0) {
                    LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth(), color = CarColors.Accent)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
                }
                Hint(Transfers.progressText(context, t.progress, t.done, t.total, t.speed, t.etaS))
            }

        is Task.Paused ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t.progress >= 0) {
                    LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth(), color = CarColors.TextDim)
                }
                Hint(stringResource(R.string.tr_paused, Transfers.percent(t.progress), Transfers.size(context, t.done), Transfers.size(context, t.total)))
            }

        Task.Installing -> {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
            Hint(stringResource(R.string.tr_installing))
        }

        Task.Queued -> Hint(stringResource(R.string.queued))
        is Task.Failed -> Text(stringResource(R.string.install_failed, t.message), color = ErrorColor, fontSize = 14.sp)
        Task.Done -> Hint(stringResource(R.string.installed_ok))
        Task.RebootNeeded -> Text(stringResource(R.string.reboot_needed), color = CarColors.Accent, fontSize = 14.sp)
        else -> {}
    }
}

/** Space between an icon and its text in rows. */
@Composable
fun Gap() = Spacer(Modifier.width(16.dp))
