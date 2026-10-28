package org.librehu.ota.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.librehu.ota.R
import kotlin.math.abs

/**
 * Key clicks chosen in LibreHU Launcher: a short sound on each tap (no move, shorter than a long press). The launcher
 * publishes the sound to play (column / extra `click`: a name of [SOUNDS], or `off`); without it (older launcher),
 * the default sound of the launcher's style. 26 sounds in `res/raw/click_*.wav`: LibreHU's own (synthesised),
 * Kenney's "Interface Sounds" (CC0) and Android's touch sounds (AOSP, Apache 2.0), see third_party/KEYCLICK-SOUNDS.md.
 * The activities feed it their touch events from `dispatchTouchEvent`.
 */
object KeyClick {
    /** Every sound, by name, in the order of the launcher's list. */
    val SOUNDS: List<Pair<String, Int>> =
        listOf(
            "soft_modern" to R.raw.click_soft_modern,
            "synth_modern_b" to R.raw.click_synth_modern_b,
            "synth_modern_c" to R.raw.click_synth_modern_c,
            "aosp_effect_tick" to R.raw.click_aosp_effect_tick,
            "aosp_keypress_standard" to R.raw.click_aosp_keypress_standard,
            "kenney_tick_002" to R.raw.click_kenney_tick_002,
            "kenney_click_001" to R.raw.click_kenney_click_001,
            "soft_retro" to R.raw.click_soft_retro,
            "synth_retro_b" to R.raw.click_synth_retro_b,
            "synth_retro_c" to R.raw.click_synth_retro_c,
            "kenney_select_003" to R.raw.click_kenney_select_003,
            "kenney_toggle_004" to R.raw.click_kenney_toggle_004,
            "kenney_bong_001" to R.raw.click_kenney_bong_001,
            "soft_factory" to R.raw.click_soft_factory,
            "synth_factory_b" to R.raw.click_synth_factory_b,
            "synth_factory_c" to R.raw.click_synth_factory_c,
            "aosp_keypress_spacebar" to R.raw.click_aosp_keypress_spacebar,
            "kenney_toggle_001" to R.raw.click_kenney_toggle_001,
            "kenney_click_005" to R.raw.click_kenney_click_005,
            "kenney_switch_002" to R.raw.click_kenney_switch_002,
            "soft_ambient" to R.raw.click_soft_ambient,
            "synth_ambient_b" to R.raw.click_synth_ambient_b,
            "synth_ambient_c" to R.raw.click_synth_ambient_c,
            "kenney_glass_005" to R.raw.click_kenney_glass_005,
            "kenney_glass_006" to R.raw.click_kenney_glass_006,
            "kenney_select_005" to R.raw.click_kenney_select_005,
        )

    /** No sound. */
    const val OFF = "off"

    /** The sound of a style when none was chosen: modern, retro…, factory, ambient. */
    fun default(style: String): String =
        when {
            style.startsWith("retro") -> "soft_retro"
            style == "factory" -> "soft_factory"
            style == "ambient" -> "soft_ambient"
            else -> "soft_modern"
        }

    private var pool: SoundPool? = null
    private val loaded = HashMap<String, Int>()
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** [sound]: a name of [SOUNDS], or [OFF]. */
    fun onTouch(
        context: Context,
        ev: MotionEvent,
        sound: String,
    ) {
        if (sound == OFF) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downAt = ev.eventTime
                load(context.applicationContext, sound)
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val tap =
                    abs(ev.rawX - downX) < slop &&
                        abs(ev.rawY - downY) < slop &&
                        ev.eventTime - downAt < ViewConfiguration.getLongPressTimeout()
                if (tap) play(sound)
            }
        }
    }

    /** Plays [sound] once now (the launcher's list, to hear a sound before choosing it). */
    fun preview(
        context: Context,
        sound: String,
    ) {
        if (sound == OFF) return
        val id = load(context.applicationContext, sound) ?: return
        // Just loaded: SoundPool plays it once ready.
        if (pool?.play(id, VOLUME, VOLUME, 1, 0, 1f) == 0) pending = id
    }

    private const val VOLUME = 0.7f
    private var pending = 0

    private fun pool(): SoundPool =
        pool ?: SoundPool
            .Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()
            .also { p ->
                p.setOnLoadCompleteListener { sp, id, status ->
                    if (status == 0 && id == pending) {
                        pending = 0
                        sp.play(id, VOLUME, VOLUME, 1, 0, 1f)
                    }
                }
                pool = p
            }

    private fun load(
        context: Context,
        sound: String,
    ): Int? {
        loaded[sound]?.let { return it }
        val res = SOUNDS.firstOrNull { it.first == sound }?.second ?: return null
        return pool().load(context, res, 1).also { loaded[sound] = it }
    }

    private fun play(sound: String) {
        val id = loaded[sound] ?: return
        pool?.play(id, VOLUME, VOLUME, 1, 0, 1f)
    }
}
