package org.librehu.ota.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.librehu.ota.R

/** "Updates (3)": a page's name with the count it shows. */
@Composable
private fun pageLabel(
    p: Page,
    updates: Int,
    running: Int,
): String {
    val n =
        when (p) {
            Page.UPDATES -> updates
            Page.DOWNLOADS -> running
            else -> 0
        }
    return stringResource(p.label) + if (n > 0) " ($n)" else ""
}

/**
 * LibreHU Apps in LibreHU Launcher's car styles. Factory (Modularer Infotainment-Baukasten): the title over the accent
 * line with the state of the last check, framed keys check / all to /system / restart, the pages as soft keys.
 * Ambient (LHU-Link): the pages as tabs, the bar Back / Check / Options (all to /system, restart, restart to TWRP).
 */
@Composable
internal fun OtaStyledFrame(
    page: Page,
    onPage: (Page) -> Unit,
    title: String,
    subtitle: String?,
    details: Boolean,
    updates: Int,
    running: Int,
    busy: Boolean,
    onRefresh: (() -> Unit)?,
    onBulk: () -> Unit,
    onReboot: () -> Unit,
    onRebootRecovery: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (RetroLook.factory) {
        var menu by remember { mutableStateOf(false) }
        FactoryScreen(
            title = title,
            back = details,
            keys =
                Page.entries.map { p ->
                    SoftKey(pageLabel(p, updates, running), p.icon, p == page && !details) { onPage(p) }
                } + listOf(null),
            actions = {
                if (subtitle != null) {
                    Text(subtitle, color = Color(0xFFD2D3D8), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(12.dp))
                }
                if (onRefresh != null) {
                    FramedKey(Modifier.size(60.dp), onClick = { if (!busy) onRefresh() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.check), tint = if (busy) Color(0xFF8A8C90) else Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                FramedKey(Modifier.size(60.dp), onClick = onBulk) {
                    Icon(Icons.Default.SystemUpdateAlt, stringResource(R.string.bulk_title), tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.layout.Box {
                    FramedKey(Modifier.size(60.dp), onClick = { menu = true }) {
                        Icon(Icons.Default.PowerSettingsNew, stringResource(R.string.reboot), tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    // Restart or recovery: a framed window with two keys, like the factory confirmations.
                    if (menu) {
                        CarAlertDialog(
                            onDismissRequest = { menu = false },
                            title = { Text(stringResource(R.string.reboot)) },
                            dismissButton = {
                                StyledPill(stringResource(R.string.reboot_recovery)) {
                                    menu = false
                                    onRebootRecovery()
                                }
                            },
                            confirmButton = {
                                StyledPill(stringResource(R.string.reboot)) {
                                    menu = false
                                    onReboot()
                                }
                            },
                        )
                    }
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = CarColors.Accent, trackColor = Color.Black)
                content()
            }
        }
    } else {
        AmbientScreen(
            title,
            keys = listOf(AmbientKey(stringResource(R.string.check), enabled = onRefresh != null && !busy) { onRefresh?.invoke() }),
            options =
                listOf(
                    AmbientOption(stringResource(R.string.bulk_title), onClick = onBulk),
                    AmbientOption(stringResource(R.string.reboot), onClick = onReboot),
                    AmbientOption(stringResource(R.string.reboot_recovery), onClick = onRebootRecovery),
                ),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (!details) AmbientTabs(Page.entries.map { pageLabel(it, updates, running) }, page.ordinal) { onPage(Page.entries[it]) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = CarColors.Accent, trackColor = Color.Black)
                if (subtitle != null) {
                    Text(subtitle, color = KitDim, fontSize = 16.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                content()
            }
        }
    }
}
