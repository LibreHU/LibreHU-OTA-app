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
 * Retro style of LibreHU Launcher (VFD amber, green LCD, cassette), followed by this app: the launcher publishes it with
 * its palette (provider `org.librehu.launcher.theme`, columns / extras `style`, `background`, `surface`,
 * `surface_high`, `on_accent`, `text`, `text_dim`, and `accent`), and the segment display font of the launcher
 * (LibreHU Seg14, DSEG14 with bare letters for the accented ones: capitals without accents, SIL OFL 1.1).
 */
object RetroLook {
    /** The launcher's palette when its style is retro, null for the modern look (or an older launcher). */
    var palette: Palette? by mutableStateOf(null)
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
        palette =
            runCatching {
                val style = c.getString(c.getColumnIndexOrThrow("style"))
                if (style == null || !style.startsWith("retro")) return@runCatching null
                val v = COLORS.map { Color(c.getInt(c.getColumnIndexOrThrow(it))) }
                Palette(c.getInt(c.getColumnIndexOrThrow("dark")) != 0, v[0], v[1], v[2], v[3], v[4], v[5], v[6])
            }.getOrNull()
    }

    /** From the launcher's THEME_CHANGED broadcast. */
    fun read(i: Intent) {
        val style = i.getStringExtra("style")
        palette =
            if (style == null || !style.startsWith("retro") || COLORS.any { !i.hasExtra(it) }) {
                null
            } else {
                val v = COLORS.map { Color(i.getIntExtra(it, 0)) }
                Palette(i.getBooleanExtra("dark", true), v[0], v[1], v[2], v[3], v[4], v[5], v[6])
            }
    }

    /** Segment display font everywhere in the retro styles, Material's otherwise. */
    fun typography(): Typography {
        if (palette == null) return Typography()
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
