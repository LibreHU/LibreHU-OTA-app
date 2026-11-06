package org.librehu.ota.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.pm.PackageInfoCompat
import org.librehu.ota.R

/*
 * Infos: the Appropriate Legal Notices of the app (GNU AGPL v3 section 5 d) and the notices of the other projects it
 * uses. Version, copyright, licence (AGPL-3.0-or-later with the additional terms of its section 7: keep "Based on
 * LibreHU", mark a modified version as such), source code (section 13), contact, and the licence texts
 * (res/raw/licence_*.txt).
 */

/** A project whose code, resources or data the app uses: author, licence, and the licence text to show. */
internal class InfoNotice(
    val name: String,
    val author: String,
    val licence: String,
    val text: Int? = null,
    /** Wording its licence asks to show, e.g. "© OpenStreetMap contributors". */
    val attribution: String? = null,
)

/** Source code of the app, offered to every user (AGPL section 13). */
private const val INFO_SOURCE = "https://github.com/LibreHU/LibreHU-OTA-app"

internal val INFO_NOTICES = listOf(
    InfoNotice("Jetpack Compose, AndroidX, Material 3", "Google, The Android Open Source Project", "Apache-2.0", R.raw.licence_apache),
    InfoNotice("Kotlin, kotlinx.coroutines", "JetBrains", "Apache-2.0", R.raw.licence_apache),
    InfoNotice("Source Sans 3", "Adobe", "SIL OFL 1.1", R.raw.licence_ofl_source_sans),
    InfoNotice("LibreHU Seg14 (DSEG14)", "keshikan", "SIL OFL 1.1", R.raw.licence_ofl_dseg),
    InfoNotice("Effect_Tick, KeypressStandard, KeypressSpacebar", "The Android Open Source Project", "Apache-2.0", R.raw.licence_apache),
    InfoNotice("Interface Sounds", "Kenney, Hugo Locurcio", "CC0 1.0", R.raw.licence_cc0_kenney),
)

private val infoBackground: Color @Composable get() = CarColors.Background
private val infoText: Color @Composable get() = CarColors.Text
private val infoDim: Color @Composable get() = CarColors.TextDim
private val infoAccent: Color @Composable get() = CarColors.Accent
private val infoChip: Color @Composable get() = CarColors.SurfaceHigh

/** Version name and code of the installed app. */
private fun versionOf(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
}.getOrDefault("?")

/** The Infos page: in a tab, or over the screen with [InfoDialog]. */
@Composable
fun InfoContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember { versionOf(context) }
    var shown by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val agpl = stringResource(R.string.info_licence_text)
    val terms = stringResource(R.string.info_terms)
    LazyColumn(
        modifier = modifier.fillMaxSize().background(infoBackground),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text(stringResource(R.string.app_name), color = infoText, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.info_version, version), color = infoDim, fontSize = 16.sp)
            }
        }
        item { Text(stringResource(R.string.info_based_on), color = infoAccent, fontSize = 18.sp, fontWeight = FontWeight.Medium) }
        item { InfoParagraph(stringResource(R.string.info_copyright)) }
        item { InfoParagraph(stringResource(R.string.info_licence)) }
        item { InfoParagraph(stringResource(R.string.info_source, INFO_SOURCE)) }
        item { InfoParagraph(stringResource(R.string.info_contact)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoChip(agpl) { shown = agpl to R.raw.licence_agpl }
                InfoChip(terms) { shown = terms to R.raw.licence_terms }
            }
        }
        if (INFO_NOTICES.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.info_others), color = infoAccent, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.info_others_hint), color = infoDim, fontSize = 15.sp)
                }
            }
            items(INFO_NOTICES) { n -> NoticeRow(n) { res -> shown = n.name to res } }
        }
    }
    shown?.let { (title, res) -> LicenceTextDialog(title, res) { shown = null } }
}

/** The Infos page inside a screen that already scrolls (no lazy list in it). */
@Composable
fun InfoColumn(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember { versionOf(context) }
    var shown by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val agpl = stringResource(R.string.info_licence_text)
    val terms = stringResource(R.string.info_terms)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.app_name), color = infoText, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.info_version, version), color = infoDim, fontSize = 16.sp)
        Text(stringResource(R.string.info_based_on), color = infoAccent, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        InfoParagraph(stringResource(R.string.info_copyright))
        InfoParagraph(stringResource(R.string.info_licence))
        InfoParagraph(stringResource(R.string.info_source, INFO_SOURCE))
        InfoParagraph(stringResource(R.string.info_contact))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoChip(agpl) { shown = agpl to R.raw.licence_agpl }
            InfoChip(terms) { shown = terms to R.raw.licence_terms }
        }
        if (INFO_NOTICES.isNotEmpty()) {
            Text(stringResource(R.string.info_others), color = infoAccent, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.info_others_hint), color = infoDim, fontSize = 15.sp)
            INFO_NOTICES.forEach { n -> NoticeRow(n) { res -> shown = n.name to res } }
        }
    }
    shown?.let { (title, res) -> LicenceTextDialog(title, res) { shown = null } }
}

/** Infos over the current screen, for an app without tabs. */
@Composable
fun InfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(infoBackground)) {
            InfoBar(stringResource(R.string.info_title), onDismiss)
            InfoContent(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LicenceTextDialog(title: String, res: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val paragraphs = remember(res) {
        runCatching { context.resources.openRawResource(res).bufferedReader().use { it.readText() } }
            .getOrDefault("")
            .split("\n\n")
            .map { it.trim('\n') }
            .filter { it.isNotBlank() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(infoBackground)) {
            InfoBar(title, onDismiss)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(paragraphs) { p -> Text(p, color = infoText, fontSize = 14.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun InfoBar(title: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = infoText, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        InfoChip(stringResource(R.string.info_close), onClick = onClose)
    }
}

@Composable
private fun InfoParagraph(text: String) {
    Text(text, color = infoText, fontSize = 16.sp)
}

@Composable
private fun InfoChip(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(50))
            .background(infoChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = infoText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NoticeRow(n: InfoNotice, open: (Int) -> Unit) {
    val click = n.text?.let { res -> Modifier.clickable { open(res) } } ?: Modifier
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(infoChip)
            .then(click)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(n.name, color = infoText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text("${n.author} · ${n.licence}", color = infoDim, fontSize = 15.sp)
        n.attribution?.let { Text(it, color = infoAccent, fontSize = 15.sp) }
    }
}
