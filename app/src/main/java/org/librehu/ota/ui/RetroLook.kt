package org.librehu.ota.ui

import android.content.Intent
import android.database.Cursor
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import org.librehu.ota.R

/**
 * Retro style of LibreHU Launcher (a display's colours drawn from its accent colour) and its font, followed by this
 * app: the launcher publishes them (provider `org.librehu.launcher.theme`, columns / extras `style`, `font`,
 * `background`, `surface`, `surface_high`, `on_accent`, `text`, `text_dim`, and `accent`). The segment display font
 * (LibreHU Seg14, DSEG14 with bare letters for the accented ones: capitals without accents, SIL OFL 1.1) is chosen
 * apart from the style.
 */
object RetroLook {
    /** The launcher's palette when its style is retro, null for the modern look (or an older launcher). */
    var palette: Palette? by mutableStateOf(null)
        private set

    /**
     * The launcher's style: modern, retro, factory (Modularer Infotainment-Baukasten: black, Source Sans 3, square)
     * or ambient (LHU-Link: black to night blue lit by the accent, light text, square). Chosen in the launcher only.
     */
    var style: String by mutableStateOf("modern")
        private set

    val factory: Boolean get() = style == "factory"
    val ambient: Boolean get() = style == "ambient"

    /** Styles whose palette the app takes from the launcher. */
    private fun styled(s: String?) = s != null && (s.startsWith("retro") || s == "factory" || s == "ambient")

    /** Square corners in the factory and ambient styles, like the original systems; Material's otherwise. */
    fun shapes(): androidx.compose.material3.Shapes =
        if (factory || ambient) {
            val square = androidx.compose.foundation.shape.RoundedCornerShape(0)
            androidx.compose.material3.Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square)
        } else {
            androidx.compose.material3.Shapes()
        }

    /** Every text style of [t] through [f]. */
    private fun mapStyles(
        t: Typography,
        f: (androidx.compose.ui.text.TextStyle) -> androidx.compose.ui.text.TextStyle,
    ) = t.copy(
        displayLarge = f(t.displayLarge),
        displayMedium = f(t.displayMedium),
        displaySmall = f(t.displaySmall),
        headlineLarge = f(t.headlineLarge),
        headlineMedium = f(t.headlineMedium),
        headlineSmall = f(t.headlineSmall),
        titleLarge = f(t.titleLarge),
        titleMedium = f(t.titleMedium),
        titleSmall = f(t.titleSmall),
        bodyLarge = f(t.bodyLarge),
        bodyMedium = f(t.bodyMedium),
        bodySmall = f(t.bodySmall),
        labelLarge = f(t.labelLarge),
        labelMedium = f(t.labelMedium),
        labelSmall = f(t.labelSmall),
    )

    /** Segment display font chosen in the launcher (an older launcher: with its retro styles). */
    var segment: Boolean by mutableStateOf(false)
        private set

    data class Palette(
        val dark: Boolean,
        val background: Color,
        val surface: Color,
        val surfaceHigh: Color,
        val accent: Color,
        val onAccent: Color,
        val text: Color,
        val textDim: Color,
    )

    private val COLORS = listOf("background", "surface", "surface_high", "accent", "on_accent", "text", "text_dim")

    /** From the provider's row (cursor on it). */
    fun read(c: Cursor) {
        segment =
            runCatching {
                val i = c.getColumnIndex("font")
                if (i >= 0) c.getString(i) == "segment" else c.getString(c.getColumnIndexOrThrow("style"))?.startsWith("retro") == true
            }.getOrDefault(false)
        palette =
            runCatching {
                val style = c.getString(c.getColumnIndexOrThrow("style"))
                this.style = style ?: "modern"
                if (!styled(style)) return@runCatching null
                val v = COLORS.map { Color(c.getInt(c.getColumnIndexOrThrow(it))) }
                Palette(c.getInt(c.getColumnIndexOrThrow("dark")) != 0, v[0], v[1], v[2], v[3], v[4], v[5], v[6])
            }.getOrNull()
    }

    /** From the launcher's THEME_CHANGED broadcast. */
    fun read(i: Intent) {
        val style = i.getStringExtra("style")
        this.style = style ?: "modern"
        segment = i.getStringExtra("font")?.let { it == "segment" } ?: (style?.startsWith("retro") == true)
        palette =
            if (!styled(style) || COLORS.any { !i.hasExtra(it) }) {
                null
            } else {
                val v = COLORS.map { Color(i.getIntExtra(it, 0)) }
                Palette(i.getBooleanExtra("dark", true), v[0], v[1], v[2], v[3], v[4], v[5], v[6])
            }
    }

    /** Segment display font everywhere when chosen in the launcher, Material's otherwise. */
    fun typography(): Typography {
        if (!segment) {
            return when {
                // Factory style: Source Sans 3 (SIL OFL 1.1), regular and semibold.
                factory -> {
                    val f =
                        FontFamily(
                            Font(R.font.source_sans3_regular),
                            Font(R.font.source_sans3_semibold, androidx.compose.ui.text.font.FontWeight.SemiBold),
                            Font(R.font.source_sans3_semibold, androidx.compose.ui.text.font.FontWeight.Bold),
                        )
                    mapStyles(Typography()) { it.copy(fontFamily = f) }
                }
                // Ambient style: thin letters like the original.
                ambient -> mapStyles(Typography()) { it.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Light) }
                else -> Typography()
            }
        }
        val f = FontFamily(Font(R.font.librehu_seg14))
        return Typography().run {
            copy(
                displayLarge = displayLarge.copy(fontFamily = f),
                displayMedium = displayMedium.copy(fontFamily = f),
                displaySmall = displaySmall.copy(fontFamily = f),
                headlineLarge = headlineLarge.copy(fontFamily = f),
                headlineMedium = headlineMedium.copy(fontFamily = f),
                headlineSmall = headlineSmall.copy(fontFamily = f),
                titleLarge = titleLarge.copy(fontFamily = f),
                titleMedium = titleMedium.copy(fontFamily = f),
                titleSmall = titleSmall.copy(fontFamily = f),
                bodyLarge = bodyLarge.copy(fontFamily = f),
                bodyMedium = bodyMedium.copy(fontFamily = f),
                bodySmall = bodySmall.copy(fontFamily = f),
                labelLarge = labelLarge.copy(fontFamily = f),
                labelMedium = labelMedium.copy(fontFamily = f),
                labelSmall = labelSmall.copy(fontFamily = f),
            )
        }
    }
}
