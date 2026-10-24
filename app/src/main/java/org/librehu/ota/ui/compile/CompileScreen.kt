package org.librehu.ota.ui.compile

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.librehu.ota.R
import org.librehu.ota.install.AppCompiler
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.Body
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.SectionTitle
import org.librehu.ota.ui.components.SwitchLine

/**
 * Native compilation ([AppCompiler]), as in LibreHU-service → Tweaks: the catalogue's installed apps and the added
 * ones with their compiler filter, one or all now, automatic only without LibreHU-service.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompileScreen() {
    val context = LocalContext.current
    val compiler = remember { AppCompiler.get(context) }
    val auto by compiler.auto.collectAsStateWithLifecycle()
    val apps by compiler.apps.collectAsStateWithLifecycle()
    val noRoot by compiler.rootMissing.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var service by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        service = withContext(Dispatchers.IO) { compiler.serviceDoesIt }
        compiler.refresh()
    }
    val native = apps.count { it.filter == "speed" || it.filter == "everything" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                SectionTitle(stringResource(R.string.aot_title))
                Hint(stringResource(R.string.aot_hint))
                if (service) {
                    Body(stringResource(R.string.aot_service))
                } else {
                    SwitchLine(stringResource(R.string.aot_auto), auto, stringResource(R.string.aot_auto_hint)) { compiler.setAuto(it) }
                }
                if (noRoot) Body(stringResource(R.string.aot_no_root), ErrorColor)
                if (apps.isNotEmpty()) Body(stringResource(R.string.aot_count, native, apps.size), if (native == apps.size) CarColors.Accent else CarColors.Text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(R.string.aot_now), selected = true) { compiler.compileAll() }
                    Pill(stringResource(R.string.aot_add)) { adding = true }
                    Pill(stringResource(R.string.aot_refresh)) { compiler.refresh() }
                }
            }
        }
        items(apps, key = { it.pkg }) { a ->
            val state =
                when {
                    a.compiling -> stringResource(R.string.aot_compiling)
                    a.error != null -> stringResource(R.string.aot_failed, a.error)
                    a.filter == null -> stringResource(R.string.aot_unknown)
                    a.filter == "speed" || a.filter == "everything" -> stringResource(R.string.aot_native, a.filter)
                    else -> stringResource(R.string.aot_not_native, a.filter)
                }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, color = CarColors.Text, fontSize = 17.sp)
                        Hint("${a.pkg} · $state")
                    }
                    Spacer(Modifier.width(10.dp))
                    Pill(stringResource(R.string.aot_one), enabled = !a.compiling, small = true) { compiler.compileOne(a.pkg) }
                    Spacer(Modifier.width(10.dp))
                    if (a.librehu) {
                        Switch(
                            checked = a.enabled,
                            onCheckedChange = { compiler.setEnabled(a.pkg, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = CarColors.Accent, checkedThumbColor = CarColors.OnAccent),
                        )
                    } else {
                        Pill(stringResource(R.string.aot_remove), small = true) { compiler.removeExtra(a.pkg) }
                    }
                }
            }
        }
        item { Hint(stringResource(R.string.aot_list_hint)) }
    }
    if (adding) AddAppDialog(compiler) { adding = false }
}

/** Installed apps not in the list yet, with a search field: the one chosen is added (and compiled). */
@Composable
private fun AddAppDialog(
    compiler: AppCompiler,
    onDismiss: () -> Unit,
) {
    var all by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { compiler.candidates() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarColors.Surface,
        title = { Text(stringResource(R.string.aot_add), color = CarColors.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.aot_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val list =
                    all.orEmpty().filter { (pkg, label) ->
                        filter.isBlank() || label.contains(filter.trim(), true) || pkg.contains(filter.trim(), true)
                    }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.first }) { (pkg, label) ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                compiler.addExtra(pkg)
                                onDismiss()
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(label, color = CarColors.Text, fontSize = 17.sp)
                            Hint(pkg)
                        }
                    }
                }
                if (all == null) Hint(stringResource(R.string.aot_loading))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel), color = CarColors.Accent) } },
    )
}
