package org.librehu.ota.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.OtaSettingsStore
import org.librehu.ota.ui.apps.AppsScreen
import org.librehu.ota.ui.components.needsChoice
import org.librehu.ota.ui.components.time
import org.librehu.ota.ui.components.working
import org.librehu.ota.ui.details.AppDetailsScreen
import org.librehu.ota.ui.details.ChoiceDialogs
import org.librehu.ota.ui.downloads.DownloadsScreen
import org.librehu.ota.ui.settings.SettingsScreen
import org.librehu.ota.ui.updates.UpdatesScreen
import org.librehu.ota.ui.zips.ZipsScreen

/** Pages of the navigation rail. */
enum class Page(
    val icon: ImageVector,
    val label: Int,
) {
    APPS(Icons.Default.Apps, R.string.nav_apps),
    UPDATES(Icons.Default.SystemUpdate, R.string.nav_updates),
    ZIPS(Icons.Default.Archive, R.string.nav_zips),
    DOWNLOADS(Icons.Default.Download, R.string.nav_downloads),
    SETTINGS(Icons.Default.Settings, R.string.nav_settings),
}

/**
 * LibreHU Apps, laid out like Aurora Store for a car screen: a navigation rail (apps, updates with their count, zips,
 * downloads with the running ones, settings), a title bar with the state of the last check, and an app's details
 * page over it. Choices an install waits for (signature, older version, /system) open wherever the user is.
 */
@Composable
fun OtaApp(
    repo: AppRepository,
    open: (String) -> Unit,
) {
    val apps by repo.apps.collectAsStateWithLifecycle()
    val busy by repo.busy.collectAsStateWithLifecycle()
    val message by repo.message.collectAsStateWithLifecycle()
    val settings by OtaSettingsStore.get(LocalContext.current).collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.APPS) }
    var details by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = details != null) { details = null }

    val updates = apps.count { it.updateAvailable && it.app.packageName !in settings.ignored }
    val running = apps.count { it.working }

    Row(Modifier.fillMaxSize().background(CarColors.Background)) {
        NavigationRail(
            containerColor = CarColors.Surface,
            modifier = Modifier.fillMaxHeight(),
            header = { Spacer(Modifier.height(8.dp)) },
        ) {
            for (p in Page.entries) {
                val count =
                    when (p) {
                        Page.UPDATES -> updates
                        Page.DOWNLOADS -> running
                        else -> 0
                    }
                NavigationRailItem(
                    selected = page == p && details == null,
                    onClick = {
                        page = p
                        details = null
                    },
                    icon = {
                        BadgedBox(badge = { if (count > 0) Badge(containerColor = CarColors.Accent, contentColor = CarColors.OnAccent) { Text("$count") } }) {
                            Icon(p.icon, null)
                        }
                    },
                    label = { Text(stringResource(p.label), fontSize = 13.sp, maxLines = 1) },
                    colors =
                        NavigationRailItemDefaults.colors(
                            selectedIconColor = CarColors.OnAccent,
                            selectedTextColor = CarColors.Accent,
                            indicatorColor = CarColors.Accent,
                            unselectedIconColor = CarColors.TextDim,
                            unselectedTextColor = CarColors.TextDim,
                        ),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val context = LocalContext.current
            val d = details
            TopBar(
                title = if (d != null) apps.firstOrNull { it.app.packageName == d }?.app?.name.orEmpty() else stringResource(page.label),
                subtitle = if (d == null) status(context, busy, message, repo.lastCheck) else null,
                onBack = if (d != null) ({ details = null }) else null,
                onRefresh = if (d == null && page != Page.SETTINGS) ({ repo.check() }) else null,
                busy = busy,
            )
            val pending by repo.pendingRecovery.collectAsStateWithLifecycle()
            if (pending.isNotEmpty()) RecoveryBanner(pending) { repo.rebootRecovery() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (d != null) {
                    AppDetailsScreen(d, repo, open)
                } else {
                    val show: (String) -> Unit = { details = it }
                    when (page) {
                        Page.APPS -> AppsScreen(repo, open, show)
                        Page.UPDATES -> UpdatesScreen(repo, open, show)
                        Page.ZIPS -> ZipsScreen()
                        Page.DOWNLOADS -> DownloadsScreen(repo, show)
                        Page.SETTINGS -> SettingsScreen(repo)
                    }
                }
            }
        }
    }
    apps.firstOrNull { it.needsChoice }?.let { ChoiceDialogs(it, repo) }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    busy: Boolean,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = if (onBack != null) 8.dp else 24.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = CarColors.Text)
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = CarColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = CarColors.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onRefresh != null) {
                IconButton(onClick = onRefresh, enabled = !busy) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.check), tint = if (busy) CarColors.TextDim else CarColors.Text, modifier = Modifier.size(28.dp))
                }
            }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = CarColors.Accent, trackColor = CarColors.Background)
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** /system installs waiting for TWRP, and the button that reboots into it. */
@Composable
private fun RecoveryBanner(
    apps: List<String>,
    reboot: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .background(CarColors.Accent, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.recovery_waiting, apps.joinToString(", ")),
            color = CarColors.OnAccent,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.TextButton(onClick = reboot) {
            Text(stringResource(R.string.recovery_reboot), color = CarColors.OnAccent, fontWeight = FontWeight.Medium)
        }
    }
}

/** State of the last check: running, GitHub limit, error, or when it was. */
private fun status(
    context: Context,
    busy: Boolean,
    message: String?,
    last: Long,
): String =
    when {
        busy -> context.getString(R.string.checking)
        message != null && message.startsWith("rate:") ->
            context.getString(R.string.rate_limited, time((message.removePrefix("rate:").toLongOrNull() ?: 0) * 1000))
        message != null -> context.getString(R.string.check_failed, message)
        last == 0L -> context.getString(R.string.never_checked)
        else -> context.getString(R.string.last_check, org.librehu.ota.ui.components.dateTime(last))
    }

/** Gap between the cards of a page. */
val PageSpacing = Arrangement.spacedBy(12.dp)
