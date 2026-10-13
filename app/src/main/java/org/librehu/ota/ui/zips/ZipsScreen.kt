package org.librehu.ota.ui.zips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
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
import org.librehu.ota.ui.components.Pill

/** Flashable zips of LibreHU-flashablesZips, and configuration files (catalogue or chosen on the unit). Root. */
@Composable
fun ZipsScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.zips_tab_zips), selected = tab == 0, icon = Icons.Default.Archive, small = true) { tab = 0 }
            Pill(stringResource(R.string.zips_tab_configs), selected = tab == 1, icon = Icons.Default.SettingsSuggest, small = true) { tab = 1 }
        }
        Box(Modifier.weight(1f)) { if (tab == 0) ZipsPanel() else ConfigsPanel() }
    }
}
