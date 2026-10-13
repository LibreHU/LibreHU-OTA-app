package org.librehu.ota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository

/** Apps, configuration files, flashable zips by category, and settings. */
@Composable
fun MainScreen(
    repo: AppRepository,
    open: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(CarColors.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Pill(stringResource(R.string.tab_apps), selected = tab == 0, icon = Icons.Default.Apps) { tab = 0 }
            Pill(stringResource(R.string.tab_configs), selected = tab == 1, icon = Icons.Default.SettingsSuggest) { tab = 1 }
            Pill(stringResource(R.string.tab_zips), selected = tab == 2, icon = Icons.Default.Archive) { tab = 2 }
            Pill(stringResource(R.string.tab_settings), selected = tab == 3, icon = Icons.Default.Settings) { tab = 3 }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> AppsScreen(repo, open)
                1 -> ConfigsScreen()
                2 -> ZipsScreen()
                else -> SettingsScreen()
            }
        }
    }
}
