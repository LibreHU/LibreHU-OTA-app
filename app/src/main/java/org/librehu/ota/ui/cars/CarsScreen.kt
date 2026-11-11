package org.librehu.ota.ui.cars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.librehu.ota.data.CarFilter
import org.librehu.ota.data.CarPacks
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.components.Card
import org.librehu.ota.ui.components.ErrorColor
import org.librehu.ota.ui.components.Pill

/**
 * Cars page: car profiles by make for LibreHU service ([CarPacks]), one card per pack, installed with one touch. A
 * search field (make, model or pack, without case or accents), then the makes and, once one is chosen, its models:
 * the list stays under them, only the packs that match, each with the models that made it match. A make the service's
 * engine cannot read says so instead of offering it.
 */
@Composable
fun CarsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<CarPacks.Pack>?>(null) }
    var installed by remember { mutableStateOf<CarPacks.Installed?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var make by rememberSaveable { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf<String?>(null) }
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
    CarsList(
        packs = packs,
        installed = installed,
        problem = problem,
        running = running,
        result = result,
        query = query,
        make = make,
        model = model,
        onQuery = { query = it },
        onMake = {
            make = it
            model = null
        },
        onModel = { model = it },
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

/** The page from its state: search and filters on top, the packs that match under them. */
@Composable
fun CarsList(
    packs: List<CarPacks.Pack>?,
    installed: CarPacks.Installed?,
    problem: String?,
    running: String?,
    result: Pair<String, Boolean>?,
    query: String,
    make: String?,
    model: String?,
    onQuery: (String) -> Unit,
    onMake: (String?) -> Unit,
    onModel: (String?) -> Unit,
    onRefresh: () -> Unit,
    onInstall: (CarPacks.Pack) -> Unit,
    onRemove: (CarPacks.Pack) -> Unit,
) {
    val all = packs.orEmpty()
    val makes = remember(all) { CarFilter.makes(all) }
    val models = remember(all, make) { make?.let { CarFilter.models(all, it) }.orEmpty() }
    val shown = remember(all, query, make, model) { CarFilter.filter(all, query, make, model) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.cars_search), color = CarColors.TextDim) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = CarColors.TextDim) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, stringResource(R.string.cars_clear), tint = CarColors.Text) }
                        }
                    },
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CarColors.Text,
                            unfocusedTextColor = CarColors.Text,
                            focusedBorderColor = CarColors.Accent,
                            unfocusedBorderColor = CarColors.TextDim,
                            cursorColor = CarColors.Accent,
                        ),
                    modifier = Modifier.weight(1f),
                )
                Pill(stringResource(R.string.cars_refresh), icon = Icons.Default.Refresh, small = true, enabled = running == null, onClick = onRefresh)
            }
            // Makes, then the models of the one chosen: the list below follows them.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill(stringResource(R.string.cars_all_makes), selected = make == null, small = true) { onMake(null) } }
                items(makes, key = { it }) { m -> Pill(m, selected = make == m, small = true) { onMake(if (make == m) null else m) } }
            }
            if (make != null && models.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Pill(stringResource(R.string.cars_all_models), selected = model == null, small = true) { onModel(null) } }
                    items(models, key = { it }) { m -> Pill(m, selected = model == m, small = true) { onModel(if (model == m) null else m) } }
                }
            }
            Text(
                if (installed == null) stringResource(R.string.cars_no_service) else stringResource(R.string.cars_hint),
                color = if (installed == null) ErrorColor else CarColors.TextDim,
                fontSize = 15.sp,
            )
            if (running != null || (packs == null && problem == null)) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent)
            problem?.let { Text(stringResource(R.string.cars_failed, it), color = ErrorColor, fontSize = 15.sp) }
            result?.let { (text, ok) -> Text(text, color = if (ok) CarColors.Accent else ErrorColor, fontSize = 15.sp) }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (packs != null && shown.isEmpty()) {
                item { Text(stringResource(R.string.cars_none), color = CarColors.TextDim, fontSize = 16.sp) }
            }
            items(shown, key = { it.pack.id }) { match ->
                PackCard(match, installed, running, onInstall, onRemove)
            }
        }
    }
}

@Composable
private fun PackCard(
    match: CarFilter.Match,
    installed: CarPacks.Installed?,
    running: String?,
    onInstall: (CarPacks.Pack) -> Unit,
    onRemove: (CarPacks.Pack) -> Unit,
) {
    val p = match.pack
    val have = installed?.packs?.get(p.id)
    val readable = installed != null && CarPacks.satisfies(p.engine, installed.engine)
    // Same SHA-256 as the index: exactly the published pack.
    val upToDate = have != null && installed.upToDate(p)
    Card {
        Text(p.name, color = CarColors.Text, fontSize = 18.sp)
        Text(
            if (p.id == CarPacks.BOXES) {
                stringResource(R.string.cars_box_types, p.profiles.size)
            } else {
                stringResource(R.string.cars_count, p.cars) + " · " + p.makes.take(4).joinToString(", ") + if (p.makes.size > 4) "…" else ""
            },
            color = CarColors.TextDim,
            fontSize = 14.sp,
        )
        // The models the search or the filters found in this pack.
        if (match.models.isNotEmpty()) {
            val names = match.models.map { (mk, m) -> if (p.models.size > 1) "$mk $m" else m }
            Text(
                stringResource(R.string.cars_models, names.take(MAX_MODELS).joinToString(", ") + if (names.size > MAX_MODELS) " (+${names.size - MAX_MODELS})" else ""),
                color = CarColors.Accent,
                fontSize = 14.sp,
            )
        }
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

/** Models listed on a card at most (then their number). */
private const val MAX_MODELS = 12
