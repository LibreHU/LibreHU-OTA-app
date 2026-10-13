package org.librehu.ota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.ota.CheckJob
import org.librehu.ota.R
import org.librehu.ota.data.History
import org.librehu.ota.data.InstallMethod
import org.librehu.ota.data.OtaSettings
import org.librehu.ota.data.OtaSettingsStore
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Checks, installs, downloads, GitHub token, and the history of what was installed or flashed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val s by OtaSettingsStore.get(context).collectAsStateWithLifecycle()
    val history by History.get(context).collectAsStateWithLifecycle()

    fun set(
        reschedule: Boolean = false,
        transform: (OtaSettings) -> OtaSettings,
    ) {
        OtaSettingsStore.update(context, transform)
        if (reschedule) CheckJob.schedule(context, force = true)
    }
    var cacheSize by remember { mutableStateOf(apkCache(context).sumOf { it.length() }) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CarColors.Background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Title(stringResource(R.string.set_checks))
                SwitchLine(stringResource(R.string.auto_check), s.autoCheck) { on -> set(true) { it.copy(autoCheck = on) } }
                if (s.autoCheck) {
                    Text(stringResource(R.string.set_interval), color = CarColors.Text, fontSize = 17.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (h in OtaSettings.INTERVALS) {
                            Pill(
                                if (h % 24 ==
                                    0
                                ) {
                                    stringResource(R.string.set_days, h / 24)
                                } else {
                                    "$h h"
                                },
                                selected = s.intervalH == h,
                                small = true,
                            ) {
                                set(true) { it.copy(intervalH = h) }
                            }
                        }
                    }
                    SwitchLine(stringResource(R.string.set_wifi_only), s.wifiOnly) { on -> set(true) { it.copy(wifiOnly = on) } }
                    SwitchLine(stringResource(R.string.set_notify), s.notify) { on -> set { it.copy(notify = on) } }
                    SwitchLine(
                        stringResource(R.string.set_auto_install),
                        s.autoInstall,
                        stringResource(R.string.set_auto_install_hint),
                    ) { on ->
                        set { it.copy(autoInstall = on) }
                    }
                }
            }
        }
        item {
            Card {
                Title(stringResource(R.string.set_install))
                Text(stringResource(R.string.set_method), color = CarColors.Text, fontSize = 17.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((m, label) in listOf(
                        InstallMethod.AUTO to R.string.set_method_auto,
                        InstallMethod.ROOT to R.string.set_method_root,
                        InstallMethod.ANDROID to R.string.set_method_android,
                    )) {
                        Pill(stringResource(label), selected = s.method == m, small = true) { set { it.copy(method = m) } }
                    }
                }
                SwitchLine(stringResource(R.string.set_compile), s.compile, stringResource(R.string.set_compile_hint)) { on ->
                    set { it.copy(compile = on) }
                }
                Text(stringResource(R.string.set_parallel), color = CarColors.Text, fontSize = 17.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (n in 1..4) Pill("$n", selected = s.parallelDownloads == n, small = true) { set { it.copy(parallelDownloads = n) } }
                }
                Text(stringResource(R.string.set_parallel_hint), color = CarColors.TextDim, fontSize = 14.sp)
                SwitchLine(stringResource(R.string.set_keep_apks), s.keepApks) { on -> set { it.copy(keepApks = on) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.set_cache, cacheSize / 1024 / 1024),
                        color = CarColors.TextDim,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(stringResource(R.string.set_cache_clear), small = true) {
                        apkCache(context).forEach { it.delete() }
                        cacheSize = 0
                    }
                }
                if (s.ignored.isNotEmpty()) {
                    Text(stringResource(R.string.set_ignored, s.ignored.size), color = CarColors.TextDim, fontSize = 14.sp)
                }
            }
        }
        item {
            Card {
                Title(stringResource(R.string.set_github))
                var token by remember { mutableStateOf(s.githubToken) }
                OutlinedTextField(
                    value = token,
                    onValueChange = { v ->
                        token = v.trim()
                        set { it.copy(githubToken = token) }
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.set_token)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.set_token_hint), color = CarColors.TextDim, fontSize = 14.sp)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title(stringResource(R.string.set_history), Modifier.weight(1f))
                if (history.isNotEmpty()) Pill(stringResource(R.string.set_history_clear), small = true) { History.clear(context) }
            }
            if (history.isEmpty()) Text(stringResource(R.string.set_history_empty), color = CarColors.TextDim, fontSize = 14.sp)
        }
        items(history.take(100)) { e ->
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Text(e.name, color = CarColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.time)),
                        color = CarColors.TextDim,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    "${e.detail} · ${e.result}",
                    color = if (e.ok) CarColors.TextDim else Color(0xFFF28B82),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private fun apkCache(context: android.content.Context): List<File> = File(context.cacheDir, "apk").listFiles()?.toList().orEmpty()

@Composable
internal fun Title(
    text: String,
    modifier: Modifier = Modifier,
) = Text(text, color = CarColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = modifier)

@Composable
internal fun SwitchLine(
    title: String,
    checked: Boolean,
    hint: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, color = CarColors.Text, fontSize = 17.sp)
            if (hint != null) Text(hint, color = CarColors.TextDim, fontSize = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = CarColors.Accent, checkedThumbColor = CarColors.OnAccent),
        )
    }
}
