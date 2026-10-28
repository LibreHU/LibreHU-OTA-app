package org.librehu.ota.ui.zips

import org.librehu.ota.ui.CarAlertDialog
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import org.librehu.ota.data.Shot
import org.librehu.ota.data.ZipIndex
import org.librehu.ota.data.Zips
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.ScreenshotStrip
import org.librehu.ota.ui.components.SectionTitle
import org.librehu.ota.ui.components.WarningColor

/** What a confirmation dialog is about to do. */
private enum class ZipAction { FLASH, IMAGE }

/**
 * Flashable zips of LibreHU-flashablesZips, filtered by category and sorted in sections (subcategories of the index),
 * related zips on one card as variants: flashed by TWRP at the next start in recovery, or an image (TWRP) written
 * straight to its partition. Root.
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
    val shown = idx?.categories.orEmpty().filter { c -> idx?.zips.orEmpty().any { !it.hidden && it.category == c.id } }
    val sections = idx?.let { zipSections(it, category) }.orEmpty()
    var expanded by remember { mutableStateOf(setOf<String>()) }

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
        if (shown.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(R.string.zips_all), selected = category.isEmpty(), small = true) { category = "" }
                    for (c in shown) Pill(c.name, selected = category == c.id, small = true) { category = c.id }
                }
                shown.firstOrNull { it.id == category }?.description?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = CarColors.TextDim, fontSize = 14.sp)
                }
            }
        }
        if (!loading && idx != null && sections.isEmpty()) {
            item { Text(stringResource(R.string.zips_empty), color = CarColors.TextDim, fontSize = 14.sp) }
        }
        for (sec in sections) {
            if (sec.categoryTitle.isNotEmpty()) {
                item(key = "cat-${sec.key}") {
                    Text(sec.categoryTitle, color = CarColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (sec.title.isNotEmpty() || sec.other) {
                item(key = "sec-${sec.key}") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle(if (sec.other) stringResource(R.string.zips_other) else sec.title)
                        if (sec.description.isNotEmpty()) Hint(sec.description)
                    }
                }
            }
            items(sec.cards, key = { "card-" + it.key }) { card ->
                ZipCard(
                    card,
                    idx,
                    running,
                    expanded,
                    onExpand = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
                    onAction = { z, action -> confirm = z to action },
                )
            }
        }
    }

    confirm?.let { (z, action) ->
        CarAlertDialog(
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

/** Zips of one group (or one zip alone), its variants in order. */
private class ZipGroup(
    val key: String,
    val zips: List<FlashZip>,
) {
    val first get() = zips.first()
    val grouped get() = first.group.isNotEmpty()
    val title get() = if (grouped) first.groupName.ifEmpty { first.name } else first.name
    val description get() = if (grouped) first.groupDescription else first.description
    val order get() = zips.minOf { it.order }
}

/** A section of the list: a subcategory of a category, with the category title before its first section ("All"). */
private class ZipSection(
    val key: String,
    val categoryTitle: String,
    val title: String,
    val description: String,
    val other: Boolean,
    val cards: List<ZipGroup>,
)

/** Visible zips of [category] (all when empty), by category, subcategory (index order) and group, sorted by "order". */
private fun zipSections(
    idx: ZipIndex,
    category: String,
): List<ZipSection> {
    val out = mutableListOf<ZipSection>()
    for (c in idx.categories) {
        if (category.isNotEmpty() && c.id != category) continue
        val zips = idx.zips.filter { !it.hidden && it.category == c.id }
        if (zips.isEmpty()) continue
        val known = c.subcategories.map { it.id }
        val bySub = zips.groupBy { if (it.subcategory in known) it.subcategory else "" }
        var catTitle = if (category.isEmpty()) c.name else ""
        val subs = c.subcategories.map { it.id to it } + ("" to null)
        for ((id, sub) in subs) {
            val list = bySub[id] ?: continue
            val cards =
                list
                    .groupBy { it.group.ifEmpty { "zip:" + it.id } }
                    .map { (k, z) -> ZipGroup(k, z.sortedWith(compareBy({ it.order }, { it.name }))) }
                    .sortedWith(compareBy({ it.order }, { it.title }))
            out +=
                ZipSection(
                    "${c.id}/$id",
                    catTitle,
                    sub?.name.orEmpty(),
                    sub?.description.orEmpty(),
                    other = sub == null && c.subcategories.isNotEmpty(),
                    cards = cards,
                )
            catTitle = ""
        }
    }
    return out
}

/** One card: title, description, screenshots, then each variant with its actions; details (description of the
 * variant, warning, licence) on demand. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZipCard(
    g: ZipGroup,
    idx: ZipIndex?,
    running: String?,
    expanded: Set<String>,
    onExpand: (String) -> Unit,
    onAction: (FlashZip, ZipAction) -> Unit,
) {
    Card {
        Text(g.title, color = CarColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        if (g.description.isNotEmpty()) Text(g.description, color = CarColors.Text, fontSize = 15.sp)
        ScreenshotStrip(g.zips.flatMap { it.screenshots }.distinct().map { Shot(it, "") }, height = 100)
        for (z in g.zips) {
            val open = z.id in expanded
            val restore = idx?.zips?.firstOrNull { it.id == z.restore }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (g.grouped) Text(z.variant.ifEmpty { z.name }, color = CarColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    listOf(z.version, "%.1f Mo".format(z.size / 1048576.0)).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = CarColors.TextDim,
                    fontSize = 13.sp,
                )
                if (!z.compatible) {
                    Text(stringResource(R.string.zips_incompatible, Build.DEVICE, z.devices.joinToString()), color = ErrorColor, fontSize = 14.sp)
                }
                if (open) {
                    if (g.grouped && z.description.isNotEmpty()) Text(z.description, color = CarColors.Text, fontSize = 14.sp)
                    if (z.warning.isNotEmpty()) Text("⚠ " + z.warning, color = WarningColor, fontSize = 14.sp)
                    if (z.license.isNotEmpty() || z.upstream.isNotEmpty()) {
                        Text(listOf(z.license, z.upstream).filter { it.isNotEmpty() }.joinToString(" · "), color = CarColors.TextDim, fontSize = 12.sp)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val enabled = running == null && z.compatible
                    Pill(stringResource(R.string.zips_flash), selected = true, enabled = enabled, small = true) { onAction(z, ZipAction.FLASH) }
                    if (z.imageUrl.isNotEmpty()) {
                        Pill(stringResource(R.string.zips_write_image, z.partition), enabled = enabled, small = true) { onAction(z, ZipAction.IMAGE) }
                    }
                    if (restore != null) {
                        Pill(stringResource(R.string.zips_restore), enabled = running == null && restore.compatible, small = true) {
                            onAction(restore, ZipAction.FLASH)
                        }
                    }
                    Pill(
                        stringResource(if (open) R.string.zips_less else R.string.zips_details),
                        icon = if (z.warning.isNotEmpty()) Icons.Default.Warning else Icons.Default.Info,
                        small = true,
                    ) { onExpand(z.id) }
                }
            }
        }
    }
}
