package org.librehu.ota.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.librehu.ota.R
import kotlin.math.abs

/**
 * Key clicks that follow LibreHU Launcher's style: a short sound on each tap, a different one by style (modern: soft
 * tick, retro: car stereo beep, factory: damped "thock", ambient: rising electronic bip). The sounds are LibreHU's
 * own, synthesised (`res/raw/click_*.wav`). Off when Android's "Touch sounds" are off. The activities feed it their
 * touch events from `dispatchTouchEvent`; a tap (no move, shorter than a long press) clicks.
 */
object KeyClick {
    private var pool: SoundPool? = null
    private val sounds = IntArray(4)
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** [style]: the launcher's (modern, retro…, factory, ambient). */
    fun onTouch(
        context: Context,
        ev: MotionEvent,
        style: String,
    ) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downAt = ev.eventTime
                load(context.applicationContext)
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val tap =
                    abs(ev.rawX - downX) < slop &&
                        abs(ev.rawY - downY) < slop &&
                        ev.eventTime - downAt < ViewConfiguration.getLongPressTimeout()
                if (tap) play(context, style)
            }
        }
    }

    private fun load(context: Context) {
        if (pool != null) return
        val p =
            SoundPool
                .Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).build()
        listOf(R.raw.click_modern, R.raw.click_retro, R.raw.click_factory, R.raw.click_ambient).forEachIndexed { i, id ->
            sounds[i] = p.load(context, id, 1)
        }
        pool = p
    }

    private fun play(
        context: Context,
        style: String,
    ) {
        val on = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0 }.getOrDefault(true)
        if (!on) return
        val i =
            when {
                style.startsWith("retro") -> 1
                style == "factory" -> 2
                style == "ambient" -> 3
                else -> 0
            }
        pool?.play(sounds[i], 0.6f, 0.6f, 1, 0, 1f)
    }
}
