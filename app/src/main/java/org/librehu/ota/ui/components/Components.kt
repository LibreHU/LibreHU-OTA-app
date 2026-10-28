package org.librehu.ota.ui.components

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.librehu.ota.R
import org.librehu.ota.data.RateLimitException
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.FactoryListTitle
import org.librehu.ota.ui.KitLight
import org.librehu.ota.ui.RetroLook
import org.librehu.ota.ui.StyledPill
import org.librehu.ota.ui.StyledSwitchRow
import org.librehu.ota.ui.carPanel
import org.librehu.ota.ui.carShape
import org.librehu.ota.ui.carStyled
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/** Red of errors and amber of warnings, readable on both themes. */
val ErrorColor = Color(0xFFF28B82)
val WarningColor = Color(0xFFFDD663)

/** Rounded block, Android Auto style. [onClick] makes the whole card a touch target. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .carPanel(24.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Rounded button: [selected] = accent (the main action), else a quiet surface button. */
@Composable
fun Pill(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    if (carStyled) return StyledPill(label, selected, icon, enabled, onClick)
    Row(
        modifier =
            Modifier
                .heightIn(min = if (small) 40.dp else 48.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected && enabled) CarColors.Accent else CarColors.SurfaceHigh)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = if (small) 16.dp else 20.dp, vertical = if (small) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color =
            when {
                selected && enabled -> CarColors.OnAccent
                enabled -> CarColors.Text
                else -> CarColors.TextDim
            }
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(if (small) 20.dp else 22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = color, fontSize = if (small) 15.sp else 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** One choice among a few, as a row of pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((value, label) in options) Pill(label, selected = value == selected, small = true) { onSelect(value) }
    }
}

/** Accent-coloured title of a card or a list section. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) = when {
    // LibreHU Launcher's car styles: the title over the accent line, or in thin letters.
    RetroLook.factory -> FactoryListTitle(text, modifier)
    RetroLook.ambient -> Text(text, color = KitLight, fontSize = 21.sp, fontWeight = FontWeight.Light, modifier = modifier)
    else -> Text(text, color = CarColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

@Composable
fun Hint(text: String) = Text(text, color = CarColors.TextDim, fontSize = 14.sp)

@Composable
fun Body(
    text: String,
    color: Color = CarColors.Text,
) = Text(text, color = color, fontSize = 16.sp)

@Composable
fun SwitchLine(
    title: String,
    checked: Boolean,
    hint: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    if (carStyled) return StyledSwitchRow(title, hint, checked, enabled, onChange)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, color = if (enabled) CarColors.Text else CarColors.TextDim, fontSize = 17.sp)
            if (hint != null) Hint(hint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = CarColors.Accent, checkedThumbColor = CarColors.OnAccent),
        )
    }
}

/** Label on the left, value on the right (details of an app). */
@Composable
fun InfoRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = CarColors.TextDim, fontSize = 15.sp, modifier = Modifier.width(170.dp))
        Text(value, color = CarColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

/** Small coloured label: the state of an app in a list. */
@Composable
fun StatusChip(
    text: String,
    accent: Boolean,
) {
    Text(
        text,
        color = if (accent) CarColors.OnAccent else CarColors.TextDim,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (accent) CarColors.Accent else CarColors.SurfaceHigh)
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Icon of an installed app (Android's), or a placeholder. */
@Composable
fun AppIcon(
    packageName: String,
    installed: Boolean,
    size: Dp = 56.dp,
) {
    val context = LocalContext.current
    val icon: Drawable? =
        remember(packageName, installed) {
            if (installed) runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull() else null
        }
    val bitmap = remember(icon) { icon?.toBitmap(192, 192)?.asImageBitmap() }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(CarColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, null, modifier = Modifier.size(size * 0.86f))
        } else {
            Icon(Icons.Default.Apps, null, tint = CarColors.TextDim, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** Big centered message of an empty list. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = CarColors.TextDim, modifier = Modifier.size(56.dp))
        Text(title, color = CarColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        if (text != null) Text(text, color = CarColors.TextDim, fontSize = 15.sp)
    }
}

@Composable
fun TitleLine(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(title, color = CarColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, color = CarColors.TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Text of a failed GitHub request: the time of the limit's reset, or the message. */
fun errorText(
    context: Context,
    e: Throwable?,
): String =
    if (e is RateLimitException) {
        context.getString(R.string.rate_limited, time(e.resetEpochS * 1000))
    } else {
        context.getString(R.string.check_failed, e?.message ?: "?")
    }

fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

/** ISO 8601 date of GitHub, shown in the device's format ("" when unreadable). */
fun isoDate(iso: String): String = runCatching { dateTime(Instant.parse(iso).toEpochMilli()) }.getOrDefault("")
