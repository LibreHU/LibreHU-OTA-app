package org.librehu.ota.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Light / dark theme and accent of LibreHU Launcher (provider `org.librehu.launcher.theme` + THEME_CHANGED
 * broadcast), or Android's night mode without the launcher. [onChange] gets (dark, accent ARGB or 0).
 */
class ThemeFollower(
    private val context: Context,
    private val onChange: (Boolean, Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    ACTION_THEME_CHANGED -> {
                        RetroLook.read(intent)
                        onChange(intent.getBooleanExtra("dark", true), intent.getIntExtra("accent", 0))
                    }
                    else -> refresh() // configuration changed (system night mode)
                }
            }
        }

    private val observer =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = refresh()
        }

    fun start() {
        val filter =
            IntentFilter().apply {
                addAction(ACTION_THEME_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            context.contentResolver.registerContentObserver(URI, false, observer)
        } catch (_: SecurityException) {
        }
        refresh()
    }

    fun stop() {
        context.unregisterReceiver(receiver)
        context.contentResolver.unregisterContentObserver(observer)
    }

    fun refresh() {
        val fromLauncher =
            try {
                context.contentResolver.query(URI, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        RetroLook.read(c)
                        (c.getInt(0) != 0) to c.getInt(1)
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        if (fromLauncher != null) {
            onChange(fromLauncher.first, fromLauncher.second)
        } else {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            onChange(night != Configuration.UI_MODE_NIGHT_NO, 0)
        }
    }

    companion object {
        const val ACTION_THEME_CHANGED = "org.librehu.action.THEME_CHANGED"
        val URI: Uri = Uri.parse("content://org.librehu.launcher.theme/theme")
    }
}
