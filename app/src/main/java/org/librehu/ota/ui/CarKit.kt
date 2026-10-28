package org.librehu.ota.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Screens of LibreHU Launcher's two car styles, for the LibreHU apps (the launcher asks the app for its style, see
 * RetroLook): factory (Modularer Infotainment-Baukasten: black, a title over a line of the accent colour, light grey
 * bars, a row of soft keys at the bottom) and ambient (LHU-Link: black lit from below by the accent colour, framed
 * panels and tabs, a bar of buttons Back / main action / Options). Drawn after LibreHU Launcher's FactoryUi.kt and
 * AmbientUi.kt (same values); LibreHU's own Material icons, no maker's artwork, logo, font or name.
 */

// --- Shared ------------------------------------------------------------------------------------------------------

internal val KitLight = Color(0xFFEEF2F6)
internal val KitDim = Color(0xFF98A3B0)
private val SHEEN = Color(0xFF75716C)
private val LINE = Color(0xFF3A3B3F)
private val TILE_TOP = Color(0xFF1D1E22)
private val TILE_BOTTOM = Color(0xFF0B0B0D)
private val TILE_BORDER = Color(0xFF2C2D31)

/** Red of a selected line or key in the ambient style (measured on the original: #CE0300 over #8B0F00). */
internal val SelectionRed = Color(0xFFCE0300)
private val SELECTION_DARK = Color(0xFF8B0F00)

/** The ambient light: the accent colour, deeper and fully saturated (e.g. #0078DC gives a deep blue like #003CC7). */
internal fun deep(accent: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.argb(255, (accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt()),
        hsv,
    )
    return Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 12f) % 360f, 1f, hsv[2] * 0.9f)))
}

/** Back of the activity (the system's Back), for the Back keys. */
@Composable
private fun rememberBack(): () -> Unit {
    val owner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    return { owner?.onBackPressedDispatcher?.onBackPressed() }
}

// --- Factory style -------------------------------------------------------------------------------------------------

/** A soft key: name above, icon under it; [selected] draws it in the accent colour (the view shown). */
internal class SoftKey(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A screen of the factory style: optional framed Back key and title over a line of the accent colour (extra framed
 * keys on its right with [actions]), the [content], and seven soft key places at the bottom (null: an empty place).
 */
@Composable
internal fun FactoryScreen(
    title: String?,
    back: Boolean,
    keys: List<SoftKey?>,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val onBack = rememberBack()
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (back) {
                    FramedKey(Modifier.size(60.dp), onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(18.dp))
                }
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                actions()
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp).background(CarColors.Accent))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        SoftKeys(keys)
    }
}

/** The bar of soft keys: name above, icon under it, thin separators, a line of the accent colour on top. */
@Composable
internal fun SoftKeys(keys: List<SoftKey?>) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(CarColors.Accent))
        Row(Modifier.fillMaxWidth().height(96.dp).background(Color(0xFF07080A)), verticalAlignment = Alignment.CenterVertically) {
            keys.forEachIndexed { i, k ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (k != null) {
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val tint = if (pressed || k.selected) CarColors.Accent else Color.White
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize().clickable(interactionSource = interaction, indication = null, onClick = k.onClick),
                        ) {
                            Text(k.label, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(k.icon, null, tint = tint, modifier = Modifier.size(36.dp).padding(top = 4.dp))
                        }
                    }
                }
                if (k != null && i < keys.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight(0.75f).background(LINE))
            }
        }
    }
}

/** A key drawn as a thin white frame on black (‹ ›, Back, Reset…), its content turning the accent colour while pressed. */
@Composable
internal fun FramedKey(
    modifier: Modifier = Modifier.size(72.dp),
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .border(BorderStroke(1.5.dp, if (pressed) CarColors.Accent else Color.White))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A framed key with a word in it (Reset, Update…). */
@Composable
internal fun FramedTextKey(
    label: String,
    onClick: () -> Unit,
) {
    FramedKey(Modifier.height(56.dp).padding(start = 12.dp), onClick = onClick) {
        Text(label, color = Color.White, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 18.dp))
    }
}

/**
 * A light grey bar of a list (settings, devices): its name on the left in dark grey, a value or keys on the right; a
 * touch darkens it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FactoryBar(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(Brush.verticalGradient(if (pressed) listOf(Color(0xFFB9BABD), Color(0xFFA7A8AB)) else listOf(Color(0xFFE2E3E5), Color(0xFFCDCED1))))
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            ).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(18.dp))
        }
        Text(label, color = Color(0xFF26272A), fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = Color(0xFF55575C), fontSize = 21.sp, maxLines = 1)
        trailing?.invoke(this)
    }
}

/** A line of a dark list (station list, recent calls): icon, name, value on the right, a thin line under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FactoryListRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (selected || pressed) CarColors.Accent else Color.White
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                icon != null -> Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
            }
            if (leading != null || icon != null) Spacer(Modifier.width(22.dp))
            Text(label, color = tint, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) Text(value, color = if (selected) CarColors.Accent else Color(0xFFD2D3D8), fontSize = 24.sp, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LINE))
    }
}

/** A list title (Station list, Contacts…) over a line of the accent colour. */
@Composable
internal fun FactoryListTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(CarColors.Accent))
    }
}

/**
 * A nearly black tile with a line of the accent colour under it and its name below (presets, favourites); pressed or
 * [selected], its name turns the accent colour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FactoryTile(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** Width / height of the tile itself (null: the height left by the name). */
    aspect: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier.combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (aspect != null) Modifier.aspectRatio(aspect) else Modifier.weight(1f))
                .background(Brush.verticalGradient(listOf(TILE_TOP, TILE_BOTTOM)))
                .border(BorderStroke(1.dp, TILE_BORDER)),
            content = content,
        )
        Box(Modifier.fillMaxWidth().height(3.dp).background(CarColors.Accent))
        Text(
            label.ifEmpty { " " },
            color = if (pressed || selected) CarColors.Accent else Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Page dots: the page shown in the accent colour. */
@Composable
internal fun PageDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .background(if (i == current) CarColors.Accent else Color.White, androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}

// --- Ambient style -------------------------------------------------------------------------------------------------

/** A key of the bottom bar: a word, or an icon; off, it is drawn at 40 % and does nothing. */
internal class AmbientKey(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** A line of the Options pop-up menu. */
internal class AmbientOption(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * A screen of the ambient style: the name of the screen over a line of the accent colour, the [content], and a bar of
 * buttons at the bottom: Back (the system's Back), the [keys] of the screen and Options (its pop-up menu) when
 * [options] has lines.
 */
@Composable
internal fun AmbientScreen(
    title: String,
    keys: List<AmbientKey>,
    options: List<AmbientOption>,
    content: @Composable BoxScope.() -> Unit,
) {
    val accent = CarColors.Accent
    val onBack = rememberBack()
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawBehind {
                // The ambient light: an oval of the deep accent rising behind the bottom bar.
                drawOval(
                    Brush.radialGradient(
                        0f to deep(accent),
                        0.55f to Color.Transparent,
                        center = Offset(size.width / 2, size.height),
                        radius = size.width * 0.5f,
                    ),
                    topLeft = Offset(0f, size.height * 0.62f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.76f),
                )
            }.safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                Text(title, color = KitLight, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp).background(accent))
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), content = content)
            Row(Modifier.fillMaxWidth().height(62.dp).padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
                AmbientBarButton(AmbientKey("", Icons.AutoMirrored.Outlined.Undo, onClick = onBack))
                keys.forEach { AmbientBarButton(it) }
                AmbientBarButton(AmbientKey("", Icons.Outlined.ListAlt, enabled = options.isNotEmpty()) { menu = true })
            }
        }
        if (menu) AmbientOptionsPopup(options) { menu = false }
    }
}

/** A button of the bars: grey sheen on top, black in the middle, the accent light at the bottom, a thin accent frame. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowScope.AmbientBarButton(b: AmbientKey) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = CarColors.Accent
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .graphicsLayer { alpha = if (b.enabled) 1f else 0.4f }
                .background(
                    Brush.verticalGradient(
                        0.0225f to SHEEN,
                        (if (pressed) 0.25f else 0.4581f) to Color.Black,
                        1f to if (pressed) accent else deep(accent),
                    ),
                ).border(BorderStroke(1.dp, accent))
                .then(
                    if (b.enabled) {
                        Modifier.combinedClickable(interactionSource = interaction, indication = null, onClick = b.onClick, onLongClick = b.onLongClick)
                    } else {
                        Modifier
                    },
                ),
    ) {
        if (b.icon != null) {
            Icon(b.icon, b.label.ifEmpty { null }, tint = KitLight, modifier = Modifier.size(30.dp))
        } else {
            Text(b.label, color = KitLight, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1)
        }
    }
}

/** A tall key on the side of a panel (◀ ▶, ⏮ ⏭): the bar buttons' light, standing up. */
@Composable
internal fun AmbientSideKey(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    width: Dp = 60.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = CarColors.Accent
    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    0f to SHEEN,
                    (if (pressed) 0.2f else 0.4f) to Color.Black,
                    1f to if (pressed) accent else deep(accent),
                ),
            ).border(BorderStroke(1.dp, accent))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = KitLight, modifier = Modifier.size(26.dp)) }
}

/** A panel: black, a thin line of the accent colour around it, its bottom edge lit when [glow]. */
@Composable
internal fun AmbientFrame(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val accent = CarColors.Accent
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .drawBehind {
                if (glow) {
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.64f to Color.Transparent, 1f to deep(accent).copy(alpha = 0.85f)))
                }
            }.border(BorderStroke(1.dp, accent)),
        content = content,
    )
}

/** Tabs like the original's: framed in the accent colour, the open one without its bottom line and in white. */
@Composable
internal fun AmbientTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val accent = CarColors.Accent
    Row(Modifier.fillMaxWidth().height(52.dp)) {
        labels.forEachIndexed { i, label ->
            val open = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawLine(accent, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                        drawLine(accent, Offset(w / 2, 0f), Offset(w / 2, size.height), w)
                        drawLine(accent, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                        if (!open) drawLine(accent, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                    }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (open) KitLight else KitDim.copy(alpha = 0.7f), fontSize = 21.sp, fontWeight = FontWeight.Light, maxLines = 1) }
        }
    }
}

/** The red light of the selected line: a band glowing from its middle, a bright point at each end. */
internal fun Modifier.selectionGlow(on: Boolean): Modifier =
    if (!on) {
        this
    } else {
        drawBehind {
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.3f to SELECTION_DARK.copy(alpha = 0.7f), 0.5f to SelectionRed, 0.7f to SELECTION_DARK.copy(alpha = 0.7f), 1f to Color.Transparent))
            val r = size.height * 0.08f
            drawCircle(SelectionRed, r, Offset(r, size.height / 2))
            drawCircle(SelectionRed, r, Offset(size.width - r, size.height / 2))
        }
    }

/** A line of a framed list: name, value on the right; [selected] lights it in red like the original. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AmbientListRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = CarColors.Accent
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .selectionGlow(selected)
                .drawBehind { if (pressed && !selected) drawRect(Brush.verticalGradient(listOf(Color.Transparent, deep(accent)))) }
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(18.dp))
            }
            Text(label, color = KitLight, fontSize = 23.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) Text(value, color = KitLight, fontSize = 21.sp, fontWeight = FontWeight.Light, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.45f)))
    }
}

/** A number in a small disc (preset, contact count): white on grey, or black on white when [strong]. */
@Composable
internal fun NumberBadge(
    n: Int,
    strong: Boolean = false,
) {
    Box(
        Modifier
            .size(30.dp)
            .background(
                if (strong) Brush.verticalGradient(listOf(Color.White, Color.White)) else Brush.verticalGradient(listOf(Color(0xFF8C8F94), Color(0xFF3E4146))),
                androidx.compose.foundation.shape.CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) { Text(n.toString(), color = if (strong) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** A word in a white box (AF, TA…). */
@Composable
internal fun FlagBox(label: String) {
    Text(
        label,
        color = Color.Black,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Color.White).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The Options pop-up menu: a column of choices over the right of the bottom bar, each line framed in the accent colour
 * on night blue, the screen dimmed behind. A touch outside closes it.
 */
@Composable
internal fun AmbientOptionsPopup(
    items: List<AmbientOption>,
    onDismiss: () -> Unit,
) {
    val accent = CarColors.Accent
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 62.dp)
                .fillMaxWidth(0.5f)
                .background(Color(0xF00E1626))
                .border(BorderStroke(1.dp, accent))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            items.forEachIndexed { i, item ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(accent))
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .drawBehind { if (pressed) drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent))) }
                        .then(
                            if (item.enabled) {
                                Modifier.clickable(interactionSource = interaction, indication = null) {
                                    onDismiss()
                                    item.onClick()
                                }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(item.label, color = if (item.enabled) KitLight else KitDim.copy(alpha = 0.6f), fontSize = 21.sp, fontWeight = FontWeight.Light)
                }
            }
        }
    }
}

/** An icon in brushed metal: white at the top fading to grey at the bottom, like the original's grey glyphs. */
@Composable
internal fun MetalIcon(
    icon: ImageVector,
    size: Dp,
) = Icon(
    icon,
    null,
    tint = Color.White,
    modifier =
        Modifier
            .size(size)
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFC9CDD3), Color(0xFF7E848D))),
                    blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn,
                )
            },
)

/** A menu tile: black in a thin frame of the accent colour, metal icon over its name; pressed, lit from the bottom. */
@Composable
internal fun AmbientMenuTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = CarColors.Accent
    Box(
        modifier
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.75f))
            .drawBehind {
                if (pressed) drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.64f to Color.Transparent, 1f to deep(accent).copy(alpha = 0.9f)))
            }.border(BorderStroke(1.dp, accent))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MetalIcon(icon, 76.dp)
            Spacer(Modifier.height(10.dp))
            Text(label, color = KitLight, fontSize = 23.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A key of a framed grid (dial pad, in-call actions): the bar buttons' light, a word or an icon. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AmbientGridKey(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = CarColors.Accent
    Box(
        modifier
            .background(
                Brush.verticalGradient(
                    0f to SHEEN.copy(alpha = 0.8f),
                    (if (pressed || selected) 0.25f else 0.45f) to Color.Black,
                    1f to if (pressed || selected) accent else deep(accent).copy(alpha = 0.6f),
                ),
            ).border(BorderStroke(1.dp, accent))
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A picture in a square frame of the accent colour (contact), the metal person when there is none. */
@Composable
internal fun FramedPicture(
    size: Dp,
    modifier: Modifier = Modifier,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    Box(modifier.size(size).border(BorderStroke(1.dp, CarColors.Accent)).padding(1.dp), contentAlignment = Alignment.Center) {
        if (image != null) {
            androidx.compose.foundation.Image(image, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            MetalIcon(Icons.Filled.Person, size * 0.62f)
        }
    }
}

// --- Generic pieces for the settings-like screens of the apps ------------------------------------------------------

/** True when the launcher asks the app for one of its car styles. */
internal val carStyled: Boolean get() = RetroLook.factory || RetroLook.ambient

/** An action of a styled screen: a soft key (factory), the bar's main key then the Options lines (ambient). */
internal class StyledAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * A whole screen in the launcher's style: factory = framed Back, title over the accent line, [actions] as soft keys;
 * ambient = title, the first action in the bottom bar, the others in Options.
 */
@Composable
internal fun StyledFrame(
    title: String,
    actions: List<StyledAction>,
    content: @Composable BoxScope.() -> Unit,
) {
    if (RetroLook.factory) {
        val keys = actions.take(7).map { SoftKey(it.label, it.icon, onClick = it.onClick) }
        FactoryScreen(title, back = true, keys = keys + List(7 - keys.size) { null }) {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), content = content)
        }
    } else {
        AmbientScreen(
            title,
            keys = actions.take(1).map { AmbientKey(it.label, onClick = it.onClick) },
            options = actions.drop(1).map { AmbientOption(it.label, onClick = it.onClick) },
            content = content,
        )
    }
}

/** A group of settings: its title over the accent line (factory), a frame of the accent colour (ambient). */
@Composable
internal fun StyledCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (RetroLook.factory) {
        Column(modifier.fillMaxWidth()) {
            if (title != null) FactoryListTitle(title)
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            if (title != null) Text(title, color = KitLight, fontSize = 21.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(BorderStroke(1.dp, CarColors.Accent))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** A key with a word (and an icon): a white frame (factory), the bar buttons' light (ambient). */
@Composable
internal fun StyledPill(
    label: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val body: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (RetroLook.factory && selected) CarColors.Accent else KitLight, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                color = if (RetroLook.factory && selected) CarColors.Accent else KitLight,
                fontSize = 19.sp,
                fontWeight = if (RetroLook.ambient) FontWeight.Light else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
    val m = Modifier.height(52.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
    if (RetroLook.factory) {
        FramedKey(m.then(if (selected) Modifier.border(BorderStroke(1.5.dp, CarColors.Accent)) else Modifier), onClick = { if (enabled) onClick() }) { body() }
    } else {
        AmbientGridKey(m, selected = selected, onClick = { if (enabled) onClick() }) { body() }
    }
}

/** A setting on / off: a light bar with a square box (factory), a line with ON | OFF keys (ambient). */
@Composable
internal fun StyledSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val m = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f }
    if (RetroLook.factory) {
        Row(
            m
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFFE2E3E5), Color(0xFFCDCED1))))
                .clickable(enabled = enabled) { onChange(!checked) }
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFF26272A), fontSize = 24.sp)
                if (subtitle != null) Text(subtitle, color = Color(0xFF55575C), fontSize = 16.sp)
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(30.dp).border(BorderStroke(2.dp, Color(0xFF26272A))), contentAlignment = Alignment.Center) {
                if (checked) Icon(Icons.Filled.Check, null, tint = Color(0xFF26272A), modifier = Modifier.size(26.dp))
            }
        }
    } else {
        Row(
            m
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(enabled = enabled) { onChange(!checked) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = KitLight, fontSize = 21.sp, fontWeight = FontWeight.Light)
                if (subtitle != null) Text(subtitle, color = KitDim, fontSize = 15.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.width(16.dp))
            // ON | OFF like the original: both brushed, the state in force marked by a red bar under its word.
            Row(Modifier.border(BorderStroke(1.dp, CarColors.Accent))) {
                for (on in listOf(true, false)) {
                    Box(
                        Modifier
                            .width(68.dp)
                            .height(46.dp)
                            .background(Brush.verticalGradient(0.0225f to SHEEN, 0.4581f to Color.Black, 1f to deep(CarColors.Accent).copy(alpha = 0.7f)))
                            .clickable(enabled = enabled) { onChange(on) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (on) "ON" else "OFF", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Light)
                        if (on == checked) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 5.dp)
                                    .width(44.dp)
                                    .height(4.dp)
                                    .background(SELECTION_DARK)
                                    .border(BorderStroke(1.dp, SelectionRed)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A line of a list: icon, name (and a second line), a value on the right; selected in the accent colour or red. */
@Composable
internal fun StyledListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    if (RetroLook.factory) {
        FactoryListRow(title, icon = icon, value = trailing ?: subtitle, selected = selected, onClick = onClick)
    } else {
        AmbientListRow(
            title,
            value = trailing ?: subtitle,
            selected = selected,
            leading = icon?.let { { MetalIcon(it, 30.dp) } },
            onClick = onClick,
        )
    }
}

/** Rounded corners in the modern look, square in the launcher's car styles. */
internal fun carShape(radius: Dp): androidx.compose.ui.graphics.Shape =
    if (carStyled) androidx.compose.ui.graphics.RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(radius)

/** A panel's background: [color] rounded (modern), none (factory), black framed in the accent colour (ambient). */
internal fun Modifier.carPanel(
    radius: Dp = 28.dp,
    color: Color = CarColors.Surface,
): Modifier =
    when {
        RetroLook.factory -> this
        RetroLook.ambient -> background(Color.Black.copy(alpha = 0.6f)).border(1.dp, CarColors.Accent)
        else -> clip(androidx.compose.foundation.shape.RoundedCornerShape(radius)).background(color)
    }
