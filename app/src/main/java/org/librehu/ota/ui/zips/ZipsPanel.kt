package org.librehu.ota.ui.zips

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.ota.R
import org.librehu.ota.data.Configs
import org.librehu.ota.data.FlashZip
import org.librehu.ota.data.ZipIndex
import org.librehu.ota.data.Zips
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.WarningColor

/** What a confirmation dialog is about to do. */
private enum class ZipAction { FLASH, IMAGE }

/**
 * Flashable zips of LibreHU-flashablesZips, filtered by category: flashed by TWRP at the next start in recovery, or
 * an image (TWRP) written straight to its partition. Root.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZipsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf<ZipIndex?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirm by remember { mutableStateOf<Pair<FlashZip, ZipAction>?>(null) }
    var askReboot by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        loading = true
        withContext(Dispatchers.IO) { Zips.load(context) }
            .onSuccess {
                index = it
                error = null
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }
    val idx = index
    val visible = idx?.zips.orEmpty().filter { !it.hidden && (category.isEmpty() || it.category == category) }
    val usedCategories = idx?.categories.orEmpty().filter { c -> idx?.zips.orEmpty().any { !it.hidden && it.category == c.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.zips_hint),
                    color = CarColors.TextDim,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Pill(stringResource(R.string.check), icon = Icons.Default.Refresh, small = true, enabled = !loading) { reload++ }
            }
            if (loading || running != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
            error?.let { Text(stringResource(R.string.zips_error, it), color = ErrorColor, fontSize = 14.sp) }
            result?.let { (text, ok) -> Text(text, color = if (ok) CarColors.Accent else ErrorColor, fontSize = 15.sp) }
        }
        if (usedCategories.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(R.string.zips_all), selected = category.isEmpty(), small = true) { category = "" }
                    for (c in usedCategories) Pill(c.name, selected = category == c.id, small = true) { category = c.id }
                }
                usedCategories.firstOrNull { it.id == category }?.description?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = CarColors.TextDim, fontSize = 14.sp)
                }
            }
        }
        if (!loading && idx != null && visible.isEmpty()) {
            item { Text(stringResource(R.string.zips_empty), color = CarColors.TextDim, fontSize = 14.sp) }
        }
        items(visible, key = { it.id }) { z ->
            val restore = idx?.zips?.firstOrNull { it.id == z.restore }
            Card {
                Text(z.name, color = CarColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                val cat = idx?.categories?.firstOrNull { it.id == z.category }?.name ?: z.category
                Text(
                    listOf(cat, z.version, "%.1f Mo".format(z.size / 1048576.0)).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = CarColors.TextDim,
                    fontSize = 13.sp,
                )
                if (z.description.isNotEmpty()) Text(z.description, color = CarColors.Text, fontSize = 15.sp)
                if (z.warning.isNotEmpty()) Text("⚠ " + z.warning, color = WarningColor, fontSize = 14.sp)
                if (!z.compatible) {
                    Text(
                        stringResource(R.string.zips_incompatible, Build.DEVICE, z.devices.joinToString()),
                        color = ErrorColor,
                        fontSize = 14.sp,
                    )
                }
                if (z.license.isNotEmpty() || z.upstream.isNotEmpty()) {
                    Text(
                        listOf(z.license, z.upstream).filter { it.isNotEmpty() }.joinToString(" · "),
                        color = CarColors.TextDim,
                        fontSize = 12.sp,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val enabled = running == null && z.compatible
                    Pill(stringResource(R.string.zips_flash), selected = true, enabled = enabled) { confirm = z to ZipAction.FLASH }
                    if (z.imageUrl.isNotEmpty()) {
                        Pill(stringResource(R.string.zips_write_image, z.partition), enabled = enabled) { confirm = z to ZipAction.IMAGE }
                    }
                    if (restore != null) {
                        Pill(stringResource(R.string.zips_restore), enabled = running == null && restore.compatible) {
                            confirm = restore to ZipAction.FLASH
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (z, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(z.name) },
            text = {
                Text(
                    if (action == ZipAction.FLASH) {
                        stringResource(R.string.zips_confirm_flash)
                    } else {
                        stringResource(R.string.zips_confirm_image, z.partition)
                    } + if (z.warning.isNotEmpty()) "\n\n⚠ " + z.warning else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    running = z.id
                    result = null
                    scope.launch {
                        val o =
                            withContext(Dispatchers.IO) {
                                if (action == ZipAction.FLASH) Zips.flash(context, z) else Zips.writeImage(context, z)
                            }
                        running = null
                        result =
                            when (o) {
                                is Configs.Outcome.Done -> {
                                    context.getString(
                                        if (action ==
                                            ZipAction.FLASH
                                        ) {
                                            R.string.cfg_zip_ready
                                        } else {
                                            R.string.zips_image_done
                                        },
                                        o.detail,
                                    ) to
                                        true
                                }

                                is Configs.Outcome.Failed -> {
                                    context.getString(R.string.cfg_failed, o.reason) to false
                                }
                            }
                        if (o is Configs.Outcome.Done && action == ZipAction.FLASH) askReboot = true
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (askReboot) {
        AlertDialog(
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
