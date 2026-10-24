package org.librehu.ota.ui.setup

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.Edition
import org.librehu.ota.data.Task
import org.librehu.ota.install.Root
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.AppIcon
import org.librehu.ota.ui.components.Body
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.Choices
import org.librehu.ota.ui.components.Hint
import org.librehu.ota.ui.components.Pill
import org.librehu.ota.ui.components.Ring
import org.librehu.ota.ui.components.SectionTitle
import org.librehu.ota.ui.components.statusLabel

/** Whether the quick setup is shown: at the first start, then from the settings. */
object QuickSetupState {
    private const val PREF_DONE = "setup_done"
    private val _shown = MutableStateFlow<Boolean?>(null)
    val shown: StateFlow<Boolean?> = _shown.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences("ota", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (_shown.value == null) _shown.value = !prefs(context).getBoolean(PREF_DONE, false)
    }

    fun open() {
        _shown.value = true
    }

    fun close(context: Context) {
        prefs(context).edit().putBoolean(PREF_DONE, true).apply()
        _shown.value = false
    }
}

/**
 * Quick setup, in four steps: the edition (which head unit software the apps talk to), root (silent installs), the
 * apps (every one of the catalogue checked; those installed and up to date left out), then their install with each
 * one's progress. Everything stays changeable later in the settings and the lists.
 */
@Composable
fun QuickSetup(repo: AppRepository) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val edition by repo.editionFlow.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val busy by repo.busy.collectAsStateWithLifecycle()
    var root by remember { mutableStateOf<Boolean?>(null) }
    var rootCheck by remember { mutableIntStateOf(0) }
    var unchecked by rememberSaveable { mutableStateOf(setOf<String>()) }
    var started by rememberSaveable { mutableStateOf(setOf<String>()) }
    LaunchedEffect(rootCheck) {
        root = null
        root = withContext(Dispatchers.IO) { Root.isAvailable() }
    }
    // Releases of the chosen edition before the list of apps.
    LaunchedEffect(step) { if (step == 2) repo.check() }

    val offered = apps.filter { it.app.packageName != context.packageName && (it.canInstall || it.updateAvailable) }
    val chosen = offered.filter { it.app.packageName !in unchecked }

    Column(Modifier.fillMaxSize().background(CarColors.Background)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.setup_title), color = CarColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Hint(stringResource(R.string.setup_step, step + 1, 4))
            }
            Pill(stringResource(R.string.setup_skip), small = true) { QuickSetupState.close(context) }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                0 ->
                    item {
                        Card {
                            SectionTitle(stringResource(R.string.setup_edition))
                            Choices(
                                listOf(
                                    Edition.LIBREHU_SERVICE to stringResource(R.string.edition_service),
                                    Edition.IVI to stringResource(R.string.edition_ivi),
                                    Edition.GENERIC to stringResource(R.string.edition_generic),
                                ),
                                edition,
                            ) { repo.setEdition(it) }
                            Body(
                                stringResource(
                                    when (edition) {
                                        Edition.LIBREHU_SERVICE -> R.string.setup_edition_service
                                        Edition.IVI -> R.string.setup_edition_ivi
                                        Edition.GENERIC -> R.string.setup_edition_generic
                                    },
                                ),
                            )
                            Hint(stringResource(R.string.edition_hint))
                        }
                    }
                1 ->
                    item {
                        Card {
                            SectionTitle(stringResource(R.string.setup_root))
                            Body(
                                stringResource(
                                    when (root) {
                                        null -> R.string.root_checking
                                        true -> R.string.root_yes
                                        false -> R.string.root_no
                                    },
                                ),
                            )
                            if (root == false) Hint(stringResource(R.string.setup_root_hint))
                            if (root != null) Pill(stringResource(R.string.setup_root_again), small = true) { rootCheck++ }
                        }
                    }
                2 -> {
                    item {
                        Card {
                            SectionTitle(stringResource(R.string.setup_apps))
                            Hint(stringResource(R.string.setup_apps_hint))
                            if (busy) Body(stringResource(R.string.setup_checking))
                            if (!busy && offered.isEmpty()) Body(stringResource(R.string.setup_apps_none))
                        }
                    }
                    items(offered, key = { it.app.packageName }) { s ->
                        AppChoice(s, s.app.packageName !in unchecked) { on ->
                            unchecked = if (on) unchecked - s.app.packageName else unchecked + s.app.packageName
                        }
                    }
                }
                else -> {
                    val list = apps.filter { it.app.packageName in started }
                    item {
                        Card {
                            SectionTitle(stringResource(R.string.setup_install))
                            val running = list.count { it.task is Task.Downloading || it.task == Task.Queued || it.task == Task.Installing }
                            Body(stringResource(if (running > 0) R.string.setup_installing else R.string.setup_installed, list.size - running, list.size))
                            Hint(stringResource(R.string.setup_install_hint))
                        }
                    }
                    items(list, key = { it.app.packageName }) { s -> AppProgress(s) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step in 1..2) Pill(stringResource(R.string.setup_back)) { step-- }
            Spacer(Modifier.weight(1f))
            when (step) {
                0, 1 -> Pill(stringResource(R.string.setup_next), selected = true) { step++ }
                2 ->
                    Pill(stringResource(R.string.setup_install_n, chosen.size), selected = true, enabled = chosen.isNotEmpty() && !busy) {
                        started = chosen.map { it.app.packageName }.toSet()
                        // LibreHU Apps orders its own update last; the others download in parallel.
                        chosen.forEach { repo.install(it.app.packageName) }
                        step = 3
                    }
                else -> Pill(stringResource(R.string.setup_done), selected = true) { QuickSetupState.close(context) }
            }
        }
    }
}

@Composable
private fun AppChoice(
    s: AppState,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(onClick = { onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = onChange,
                colors = CheckboxDefaults.colors(checkedColor = CarColors.Accent, checkmarkColor = CarColors.OnAccent),
            )
            Spacer(Modifier.width(8.dp))
            AppIcon(s.app.packageName, s.installed, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.app.name, color = CarColors.Text, fontSize = 17.sp)
                Hint(listOf(s.app.description, statusLabel(s).first, s.release?.versionName.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
            }
        }
    }
}

@Composable
private fun AppProgress(s: AppState) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(s.app.packageName, s.installed, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.app.name, color = CarColors.Text, fontSize = 17.sp)
                val (label, _) = statusLabel(s)
                val t = s.task
                Hint(if (t is Task.Failed) "$label: ${t.message}" else label)
            }
            when (val t = s.task) {
                is Task.Downloading -> Ring(t.progress)
                Task.Queued, Task.Installing -> Ring(-1f)
                else -> {}
            }
        }
    }
}
