package org.librehu.ota

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import org.librehu.ota.data.AppRepository
import org.librehu.ota.ui.AppsScreen
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.CarPalette
import org.librehu.ota.ui.CarTheme
import org.librehu.ota.ui.ThemeFollower

/** LibreHU Apps: the LibreHU apps, their versions on GitHub, install and update. */
class MainActivity : ComponentActivity() {
    private val theme =
        lazy {
            ThemeFollower(this) { dark, accent ->
                CarColors.palette = CarPalette.of(dark, if (accent != 0) Color(accent) else null)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.value.start()
        CheckJob.schedule(this)
        val repo = AppRepository.get(this)
        repo.refreshInstalled()
        if (System.currentTimeMillis() - repo.lastCheck > RECHECK_MS) repo.check()
        setContent { CarTheme { AppsScreen(repo, ::open) } }
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's installer or an uninstall.
        AppRepository.get(this).refreshInstalled()
    }

    override fun onDestroy() {
        theme.value.stop()
        super.onDestroy()
    }

    private fun open(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private companion object {
        /** Opening the app checks again after this delay (GitHub allows 60 unauthenticated requests per hour). */
        const val RECHECK_MS = 10 * 60 * 1000L
    }
}
