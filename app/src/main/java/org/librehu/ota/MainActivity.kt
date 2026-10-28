package org.librehu.ota

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import org.librehu.ota.data.AppRepository
import org.librehu.ota.ui.CarColors
import org.librehu.ota.ui.CarPalette
import org.librehu.ota.ui.CarTheme
import org.librehu.ota.ui.OtaApp
import org.librehu.ota.ui.ThemeFollower

/** LibreHU Apps: the LibreHU apps, their versions on GitHub, install and update. */
class MainActivity : ComponentActivity() {
    /** A click on each tap, the sound of LibreHU Launcher's style (see KeyClick). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        org.librehu.ota.ui.KeyClick.onTouch(this, ev, org.librehu.ota.ui.RetroLook.style)
        return super.dispatchTouchEvent(ev)
    }

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
        org.librehu.ota.ui.setup.QuickSetupState
            .load(this)
        // Native code for the apps not compiled yet, a minute after (without LibreHU-service only).
        org.librehu.ota.install.CompileJob
            .schedule(this)
        val repo = AppRepository.get(this)
        repo.refreshInstalled()
        // Always a fresh list with network; without, the one kept from the last check, and a check as soon as the
        // network is back.
        repo.checkIfOnline()
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(network)
        }
        setContent { CarTheme { OtaApp(repo, ::open) } }
    }

    private val network =
        object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: android.net.Network) {
                val repo = AppRepository.get(this@MainActivity)
                if (repo.offline.value) repo.check()
            }
        }

    override fun onResume() {
        super.onResume()
        // Back from Android's installer or an uninstall.
        AppRepository.get(this).refreshInstalled()
    }

    override fun onDestroy() {
        runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(network) }
        theme.value.stop()
        super.onDestroy()
    }

    private fun open(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
