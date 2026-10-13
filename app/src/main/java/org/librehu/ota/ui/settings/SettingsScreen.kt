package org.librehu.ota.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.librehu.ota.CheckJob
import org.librehu.ota.R
import org.librehu.ota.Transfers
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.Catalog
import org.librehu.ota.data.Edition
import org.librehu.ota.data.InstallMethod
import org.librehu.ota.data.OtaSettings
import org.librehu.ota.data.OtaSettingsStore
import org.librehu.ota.data.SystemScope
import org.librehu.ota.data.SystemWay
import org.librehu.ota.data.Zips
import org.librehu.ota.install.Root
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.Body
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.Choices
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.InfoRow
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.SectionTitle
import org.librehu.ota.ui.components.SwitchLine
import java.io.File

/** Edition and root, checks, installs, /system, sources, storage, about. */
@Composable
fun SettingsScreen(repo: AppRepository) {
    val context = LocalContext.current
    val s by OtaSettingsStore.get(context).collectAsStateWithLifecycle()
    val edition by repo.editionFlow.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    var root by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { root = withContext(Dispatchers.IO) { Root.isAvailable() } }
    var cacheSize by remember { mutableLongStateOf(apkCache(context).sumOf { it.length() }) }

    fun set(
        reschedule: Boolean = false,
        transform: (OtaSettings) -> OtaSettings,
    ) {
        OtaSettingsStore.update(context, transform)
        if (reschedule) CheckJob.schedule(context, force = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                SectionTitle(stringResource(R.string.set_general))
                Body(stringResource(R.string.edition))
                Choices(
                    listOf(
                        Edition.LIBREHU_SERVICE to stringResource(R.string.edition_service),
                        Edition.IVI to stringResource(R.string.edition_ivi),
                        Edition.GENERIC to stringResource(R.string.edition_generic),
                    ),
                    edition,
                ) { repo.setEdition(it) }
                Hint(stringResource(R.string.edition_hint))
                Hint(
                    stringResource(
                        when (root) {
                            null -> R.string.root_checking
                            true -> R.string.root_yes
                            false -> R.string.root_no
                        },
                    ),
                )
            }
        }
        item {
            Card {
                SectionTitle(stringResource(R.string.set_checks))
                SwitchLine(stringResource(R.string.auto_check), s.autoCheck) { on -> set(true) { it.copy(autoCheck = on) } }
                if (s.autoCheck) {
                    Body(stringResource(R.string.set_interval))
                    Choices(OtaSettings.INTERVALS.map { h -> h to if (h % 24 == 0) stringResource(R.string.set_days, h / 24) else "$h h" }, s.intervalH) { h ->
                        set(true) { it.copy(intervalH = h) }
                    }
                    SwitchLine(stringResource(R.string.set_wifi_only), s.wifiOnly) { on -> set(true) { it.copy(wifiOnly = on) } }
                    SwitchLine(stringResource(R.string.set_notify), s.notify) { on -> set { it.copy(notify = on) } }
                    SwitchLine(stringResource(R.string.set_auto_install), s.autoInstall, stringResource(R.string.set_auto_install_hint)) { on ->
                        set { it.copy(autoInstall = on) }
                    }
                }
                SwitchLine(stringResource(R.string.set_notify_transfers), s.notifyTransfers, stringResource(R.string.set_notify_transfers_hint)) { on ->
                    set { it.copy(notifyTransfers = on) }
                }
            }
        }
        item {
            Card {
                SectionTitle(stringResource(R.string.set_install))
                Body(stringResource(R.string.set_method))
                Choices(
                    listOf(
                        InstallMethod.AUTO to stringResource(R.string.set_method_auto),
                        InstallMethod.ROOT to stringResource(R.string.set_method_root),
                        InstallMethod.ANDROID to stringResource(R.string.set_method_android),
                    ),
                    s.method,
                ) { m -> set { it.copy(method = m) } }
                SwitchLine(stringResource(R.string.set_compile), s.compile, stringResource(R.string.set_compile_hint)) { on -> set { it.copy(compile = on) } }
                Body(stringResource(R.string.set_parallel))
                Choices((1..4).map { it to "$it" }, s.parallelDownloads) { n -> set { it.copy(parallelDownloads = n) } }
                Hint(stringResource(R.string.set_parallel_hint))
                if (s.ignored.isNotEmpty()) {
                    Body(stringResource(R.string.set_ignored, s.ignored.size))
                    for (pkg in s.ignored.sorted()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(apps.firstOrNull { it.app.packageName == pkg }?.app?.name ?: pkg, color = CarColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Pill(stringResource(R.string.unignore), small = true) { repo.setIgnored(pkg, false) }
                        }
                    }
                }
            }
        }
        item {
            Card {
                SectionTitle(stringResource(R.string.set_system))
                SwitchLine(stringResource(R.string.set_system_auto), s.systemAuto, stringResource(R.string.set_system_auto_hint), enabled = root == true) { on ->
                    set { it.copy(systemAuto = on) }
                }
                if (s.systemAuto) {
                    Body(stringResource(R.string.set_system_scope))
                    Choices(
                        listOf(
                            SystemScope.SYSTEM_APPS to stringResource(R.string.set_system_scope_system),
                            SystemScope.ALL to stringResource(R.string.set_system_scope_all),
                        ),
                        s.systemScope,
                    ) { v -> set { it.copy(systemScope = v) } }
                    Body(stringResource(R.string.set_system_way))
                    Choices(
                        listOf(
                            SystemWay.AUTO to stringResource(R.string.set_system_way_auto),
                            SystemWay.MODULE to stringResource(R.string.set_system_way_module),
                            SystemWay.DIRECT to stringResource(R.string.set_system_way_direct),
                        ),
                        s.systemWay,
                    ) { v -> set { it.copy(systemWay = v) } }
                    SwitchLine(stringResource(R.string.set_system_data), s.systemAlsoData, stringResource(R.string.set_system_data_hint)) { on ->
                        set { it.copy(systemAlsoData = on) }
                    }
                }
            }
        }
        item {
            Card {
                SectionTitle(stringResource(R.string.set_github))
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
                Hint(stringResource(R.string.set_token_hint))
                var zipsUrl by remember { mutableStateOf(s.zipsIndexUrl) }
                OutlinedTextField(
                    value = zipsUrl,
                    onValueChange = { v ->
                        zipsUrl = v.trim()
                        set { it.copy(zipsIndexUrl = zipsUrl) }
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.set_zips_index)) },
                    placeholder = { Text(Zips.INDEX_URL, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Card {
                SectionTitle(stringResource(R.string.set_storage))
                SwitchLine(stringResource(R.string.set_keep_apks), s.keepApks) { on -> set { it.copy(keepApks = on) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_cache_size, Transfers.size(context, cacheSize)), color = CarColors.TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Pill(stringResource(R.string.set_cache_clear), small = true, enabled = cacheSize > 0) {
                        apkCache(context).forEach { it.delete() }
                        cacheSize = 0
                    }
                }
            }
        }
        item {
            Card {
                SectionTitle(stringResource(R.string.set_about))
                val me = apps.firstOrNull { it.app.packageName == context.packageName }
                InfoRow(stringResource(R.string.app_name), me?.installedName ?: "?")
                InfoRow(stringResource(R.string.set_about_catalog), Catalog.REMOTE_URL.substringAfter("://"))
                InfoRow(stringResource(R.string.set_about_zips), Zips.indexUrl(context).substringAfter("://"))
                Hint(stringResource(R.string.set_about_hint))
            }
        }
    }
}

private fun apkCache(context: android.content.Context): List<File> = File(context.cacheDir, "apk").listFiles()?.toList().orEmpty()
