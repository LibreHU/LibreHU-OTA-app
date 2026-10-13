package org.librehu.ota.ui.details

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.Task
import org.librehu.ota.install.Root
import org.librehu.ota.install.SystemInstall
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.WarningColor

/** The choice an install of [s] waits for, whatever page is shown. */
@Composable
fun ChoiceDialogs(
    s: AppState,
    repo: AppRepository,
) {
    when (val t = s.task) {
        is Task.Incompatible -> IncompatibleDialog(s, repo)
        is Task.Downgrade -> DowngradeDialog(s, repo)
        is Task.SystemChoice -> SystemDialog(s, t, repo)
        else -> {}
    }
}

/** Signed with another key than the installed one: uninstall first (data lost), or replace the /system copy. */
@Composable
private fun IncompatibleDialog(
    s: AppState,
    repo: AppRepository,
) {
    val pkg = s.app.packageName
    AlertDialog(
        onDismissRequest = { repo.dismiss(pkg) },
        title = { Text(stringResource(R.string.incompatible_title, s.app.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.incompatible_text))
                if (s.systemApp) Text(stringResource(R.string.incompatible_system))
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { repo.reinstall(pkg) }) { Text(stringResource(R.string.uninstall_install)) }
                if (s.systemApp) TextButton(onClick = { repo.replaceSystem(pkg) }) { Text(stringResource(R.string.replace_system)) }
            }
        },
        dismissButton = { TextButton(onClick = { repo.dismiss(pkg) }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Older build than the installed one: Android needs it uninstalled first, or (root) the data kept. */
@Composable
private fun DowngradeDialog(
    s: AppState,
    repo: AppRepository,
) {
    val pkg = s.app.packageName
    var root by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { root = withContext(Dispatchers.IO) { Root.isAvailable() } }
    AlertDialog(
        onDismissRequest = { repo.dismiss(pkg) },
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
                TextButton(onClick = { repo.reinstall(pkg) }) { Text(stringResource(R.string.uninstall_install)) }
                if (root) TextButton(onClick = { repo.reinstall(pkg, keepData = true) }) { Text(stringResource(R.string.downgrade_keep)) }
            }
        },
        dismissButton = { TextButton(onClick = { repo.dismiss(pkg) }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/**
 * /system/priv-app (privileged permissions, allow-listed) or /system/app, the fitting one preselected; as a root module
 * (the partition stays untouched) or written in /system.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemDialog(
    s: AppState,
    t: Task.SystemChoice,
    repo: AppRepository,
) {
    val pkg = s.app.packageName
    val plan = t.plan
    var privApp by remember(t.apk) { mutableStateOf(plan.recommendPrivApp) }
    val best = stringResource(R.string.recommended)
    AlertDialog(
        onDismissRequest = { repo.dismiss(pkg) },
        title = { Text(stringResource(R.string.system_title, s.app.name)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plan.readOnly?.let {
                    Text(
                        if (it == SystemInstall.WRITE_PROTECTED) {
                            stringResource(R.string.system_write_protected)
                        } else {
                            stringResource(R.string.system_read_only, it)
                        },
                        color = ErrorColor,
                    )
                }
                Text(stringResource(R.string.system_text))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in listOf(true, false)) {
                        val label = stringResource(if (p) R.string.system_priv_app else R.string.system_app)
                        Pill(label + if (p == plan.recommendPrivApp) " · $best" else "", selected = privApp == p, small = true) { privApp = p }
                    }
                }
                plan.current?.let { Hint(stringResource(R.string.system_current, it)) }
                if (plan.privileged.isEmpty()) {
                    Text(stringResource(R.string.system_no_privileged))
                } else {
                    Text(stringResource(R.string.system_privileged, plan.privileged.size))
                    Hint(plan.privileged.joinToString("\n") { "• " + it.substringAfterLast('.') })
                }
                if (plan.otherSignature) Text(stringResource(R.string.system_other_signature), color = ErrorColor)
                if (plan.manager != null) Hint(stringResource(R.string.system_module_text, plan.manager.label)) else Hint(stringResource(R.string.system_no_module))
                if (plan.needsMetamodule) Text(stringResource(R.string.system_metamodule), color = WarningColor, fontSize = 13.sp)
                Hint(stringResource(R.string.system_warning))
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                plan.manager?.let { m ->
                    TextButton(onClick = { repo.installSystem(pkg, privApp, module = true) }) {
                        Text(stringResource(R.string.system_as_module, m.label) + " · $best")
                    }
                }
                TextButton(onClick = { repo.installSystemRecovery(pkg, privApp) }) {
                    Text(stringResource(R.string.system_twrp) + if (plan.manager == null) " · $best" else "")
                }
                if (plan.readOnly == null) {
                    TextButton(onClick = { repo.installSystem(pkg, privApp, module = false) }) { Text(stringResource(R.string.system_write)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = { repo.dismiss(pkg) }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Log lines of the app, newest at the bottom; refresh and export to a file. */
@Composable
fun LogsDialog(
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
                scope.launch(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } } }
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
        "E", "F" -> ErrorColor
        "W" -> WarningColor
        "D", "V" -> CarColors.TextDim
        else -> CarColors.Text
    }
