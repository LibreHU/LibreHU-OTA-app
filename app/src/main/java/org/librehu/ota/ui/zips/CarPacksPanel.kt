package org.librehu.ota.ui.zips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.ota.R
import org.librehu.ota.data.CarPacks
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Pill

/**
 * Car profiles by make for LibreHU service ([CarPacks]): one card per make, installed with one touch; a make the
 * service's engine cannot read says so instead of offering it.
 */
@Composable
fun CarPacksPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<CarPacks.Pack>?>(null) }
    var installed by remember { mutableStateOf<CarPacks.Installed?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        problem = null
        installed = CarPacks.installed(context)
        packs =
            try {
                withContext(Dispatchers.IO) { CarPacks.index() }
            } catch (e: Exception) {
                problem = e.message ?: e.javaClass.simpleName
                null
            }
    }
    CarPacksList(
        packs = packs,
        installed = installed,
        problem = problem,
        running = running,
        result = result,
        onRefresh = { reload++ },
        onInstall = { p ->
            running = p.id
            scope.launch {
                val why = CarPacks.install(context, p)
                result = if (why == null) context.getString(R.string.cars_installed_ok, p.name, p.revision) to true else context.getString(R.string.cars_failed, why) to false
                running = null
                installed = CarPacks.installed(context)
            }
        },
        onRemove = { p ->
            running = p.id
            scope.launch {
                CarPacks.remove(context, p.id)
                running = null
                result = null
                installed = CarPacks.installed(context)
            }
        },
    )
}

/** The list itself, from its state (also drawn by the screenshot tests). */
@Composable
fun CarPacksList(
    packs: List<CarPacks.Pack>?,
    installed: CarPacks.Installed?,
    problem: String?,
    running: String?,
    result: Pair<String, Boolean>?,
    onRefresh: () -> Unit,
    onInstall: (CarPacks.Pack) -> Unit,
    onRemove: (CarPacks.Pack) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (installed == null) stringResource(R.string.cars_no_service) else stringResource(R.string.cars_hint),
                    color = if (installed == null) ErrorColor else CarColors.TextDim,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Pill(stringResource(R.string.cars_refresh), icon = Icons.Default.Refresh, small = true, enabled = running == null, onClick = onRefresh)
            }
            if (running != null || (packs == null && problem == null)) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
            problem?.let { Text(stringResource(R.string.cars_failed, it), color = ErrorColor, fontSize = 15.sp) }
            result?.let { (text, ok) -> Text(text, color = if (ok) CarColors.Accent else ErrorColor, fontSize = 15.sp) }
        }
        items(packs.orEmpty(), key = { it.id }) { p ->
            val have = installed?.packs?.get(p.id)
            val readable = installed != null && CarPacks.satisfies(p.engine, installed.engine)
            // Same SHA-256 as the index: exactly the published pack.
            val upToDate = have != null && installed.upToDate(p)
            Card {
                Text(p.name, color = CarColors.Text, fontSize = 18.sp)
                Text(
                    stringResource(R.string.cars_count, p.cars) + " · " + p.makes.take(4).joinToString(", ") + if (p.makes.size > 4) "…" else "",
                    color = CarColors.TextDim,
                    fontSize = 14.sp,
                )
                Text(
                    when {
                        installed != null && !readable -> stringResource(R.string.cars_needs_service)
                        have == null -> stringResource(R.string.cars_not_installed)
                        upToDate -> stringResource(R.string.cars_up_to_date, have)
                        else -> stringResource(R.string.cars_update_ready, p.revision)
                    },
                    color = if (installed != null && !readable) ErrorColor else if (have != null && !upToDate) CarColors.Accent else CarColors.TextDim,
                    fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (readable && !upToDate) {
                        Pill(
                            stringResource(if (have == null) R.string.cars_install else R.string.cars_update),
                            selected = true,
                            icon = Icons.Default.Download,
                            enabled = running == null,
                        ) { onInstall(p) }
                    }
                    if (have != null) {
                        Pill(stringResource(R.string.cars_remove), icon = Icons.Default.Delete, enabled = running == null) { onRemove(p) }
                    }
                }
            }
        }
    }
}
