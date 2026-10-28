package org.librehu.ota.ui.zips

import org.librehu.ota.ui.CarAlertDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.ota.R
import org.librehu.ota.data.ConfigItem
import org.librehu.ota.data.ConfigType
import org.librehu.ota.data.Configs
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.SectionTitle

/**
 * Configuration files (audio policy, CAN box config…) and recovery zips: those of the catalogue, or files chosen on the
 * device. Root; nothing is written on a partition protected by dm-verity.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { Configs.items(context) }
    var running by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var askReboot by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfigItem?>(null) }
    var dest by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("0644") }

    fun outcome(
        o: Configs.Outcome,
        zip: Boolean,
    ) {
        running = null
        when (o) {
            is Configs.Outcome.Done -> {
                result = context.getString(if (zip) R.string.cfg_zip_ready else R.string.cfg_done, o.detail) to true
                if (zip) askReboot = true
            }

            is Configs.Outcome.Failed -> {
                result = context.getString(R.string.cfg_failed, o.reason) to false
            }
        }
    }
    val pickFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            running = dest
            scope.launch { outcome(withContext(Dispatchers.IO) { Configs.applyLocal(context, uri, dest.trim(), mode.trim()) }, false) }
        }
    val pickZip =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            running = "zip"
            scope.launch { outcome(withContext(Dispatchers.IO) { Configs.flashLocal(context, uri) }, true) }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.cfg_hint), color = CarColors.TextDim, fontSize = 15.sp)
            if (running != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
            result?.let { (text, ok) -> Text(text, color = if (ok) CarColors.Accent else ErrorColor, fontSize = 15.sp) }
        }
        item { SectionTitle(stringResource(R.string.cfg_catalog)) }
        if (items.isEmpty()) item { Text(stringResource(R.string.cfg_catalog_empty), color = CarColors.TextDim, fontSize = 14.sp) }
        items(items, key = { it.id }) { c ->
            Card {
                Text(c.name, color = CarColors.Text, fontSize = 18.sp)
                if (c.description.isNotEmpty()) Text(c.description, color = CarColors.TextDim, fontSize = 14.sp)
                Text(
                    if (c.type == ConfigType.FILE) "→ ${c.dest}" else stringResource(R.string.cfg_type_zip),
                    color = CarColors.TextDim,
                    fontSize = 13.sp,
                )
                Pill(stringResource(if (c.type == ConfigType.FILE) R.string.cfg_apply else R.string.cfg_flash), enabled = running == null) {
                    confirm = c
                }
            }
        }
        item {
            Card {
                SectionTitle(stringResource(R.string.cfg_local_file))
                Text(stringResource(R.string.cfg_local_file_hint), color = CarColors.TextDim, fontSize = 14.sp)
                OutlinedTextField(
                    value = dest,
                    onValueChange = { dest = it.trim() },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cfg_dest)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in listOf(
                        "/vendor/etc/audio_policy_configuration.xml",
                        "/vendor/etc/audio_effects.xml",
                        "/jancar/config/can_config.json",
                        "/jancar/config/ivi-customer.ini",
                    )) {
                        Pill(p.substringAfterLast('/'), selected = dest == p, small = true) { dest = p }
                    }
                }
                OutlinedTextField(
                    value = mode,
                    onValueChange = { mode = it.filter { c -> c in '0'..'7' }.take(4) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cfg_mode)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Pill(stringResource(R.string.cfg_pick_file), enabled = running == null && dest.startsWith("/")) {
                    pickFile.launch(arrayOf("*/*"))
                }
            }
        }
        item {
            Card {
                SectionTitle(stringResource(R.string.cfg_local_zip))
                Text(stringResource(R.string.cfg_local_zip_hint), color = CarColors.TextDim, fontSize = 14.sp)
                Pill(stringResource(R.string.cfg_pick_zip), enabled = running == null) {
                    pickZip.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            }
        }
    }

    confirm?.let { c ->
        CarAlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.name) },
            text = {
                Text(
                    if (c.type == ConfigType.FILE) {
                        stringResource(R.string.cfg_confirm_file, c.dest)
                    } else {
                        stringResource(R.string.cfg_confirm_zip)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    running = c.id
                    scope.launch { outcome(withContext(Dispatchers.IO) { Configs.apply(context, c) }, c.type == ConfigType.RECOVERY_ZIP) }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (askReboot) {
        CarAlertDialog(
            onDismissRequest = { askReboot = false },
            title = { Text(stringResource(R.string.cfg_reboot_title)) },
            text = { Text(stringResource(R.string.cfg_reboot_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askReboot = false
                    scope.launch(Dispatchers.IO) { Configs.rebootRecovery() }
                }) { Text(stringResource(R.string.cfg_reboot_now)) }
            },
            dismissButton = { TextButton(onClick = { askReboot = false }) { Text(stringResource(R.string.cfg_reboot_later)) } },
        )
    }
}
