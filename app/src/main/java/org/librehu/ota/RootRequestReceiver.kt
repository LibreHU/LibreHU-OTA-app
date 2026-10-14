package org.librehu.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * LibreHU: root for every LibreHU app at once (LibreHU Launcher's first start assistant). On
 * `org.librehu.action.REQUEST_ROOT` sent to this package, runs `su -c id` once so that SuperSU shows this app's prompt
 * now (to grant with "Remember"), then answers `org.librehu.action.ROOT_RESULT` (extras `package`, `granted`) to the
 * package named in `reply_to`. Nothing but `id` is run; a second request within 20 s is ignored.
 */
class RootRequestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_REQUEST) return
        val replyTo = intent.getStringExtra(EXTRA_REPLY_TO)?.takeIf { PACKAGE.matches(it) } ?: return
        val now = SystemClock.elapsedRealtime()
        if (last != 0L && now - last < MIN_INTERVAL_MS) return
        last = now
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            val granted =
                try {
                    val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                    p.outputStream.close()
                    // SuperSU waits for the answer; a background broadcast may take up to a minute.
                    val done = p.waitFor(WAIT_S, TimeUnit.SECONDS)
                    if (!done) p.destroy()
                    done && p.inputStream.bufferedReader().readText().contains("uid=0")
                } catch (_: Exception) {
                    false
                }
            try {
                app.sendBroadcast(
                    Intent(ACTION_RESULT)
                        .setPackage(replyTo)
                        .putExtra("package", app.packageName)
                        .putExtra("granted", granted),
                )
            } finally {
                pending.finish()
            }
        }, "librehu-root").start()
    }

    companion object {
        const val ACTION_REQUEST = "org.librehu.action.REQUEST_ROOT"
        const val ACTION_RESULT = "org.librehu.action.ROOT_RESULT"
        const val EXTRA_REPLY_TO = "reply_to"
        private const val WAIT_S = 50L
        private const val MIN_INTERVAL_MS = 20_000L
        private val PACKAGE = Regex("[A-Za-z0-9_.]{1,100}")

        @Volatile
        private var last = 0L
    }
}
