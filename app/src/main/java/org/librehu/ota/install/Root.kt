package org.librehu.ota.install

import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** `su` (Magisk, KernelSU, KernelSU Next, APatch, SuperSU): silent installs. */
object Root {
    private const val TAG = "LibreHU-OTA"
    private const val RETRY_MS = 30_000L

    /** Time given to `su` to answer (its manager may show a prompt), then once more after [PAUSE_MS]. */
    private const val CHECK_TIMEOUT_S = 15L
    private const val PAUSE_MS = 2_000L

    @Volatile
    private var available: Boolean? = null

    @Volatile
    private var checkedAt = 0L

    /**
     * True when `su` grants root; a refusal is asked again after 30 s (KernelSU grants from its manager). A `su` that
     * does not answer no longer blocks: 15 s, then one retry (one check at a time, the others wait for its answer).
     */
    @Synchronized
    fun isAvailable(): Boolean {
        available?.let { if (it || System.currentTimeMillis() - checkedAt < RETRY_MS) return it }
        var ok = run("id", timeoutS = CHECK_TIMEOUT_S)?.output?.contains("uid=0") == true
        if (!ok) {
            Thread.sleep(PAUSE_MS)
            ok = run("id", timeoutS = CHECK_TIMEOUT_S)?.output?.contains("uid=0") == true
        }
        available = ok
        checkedAt = System.currentTimeMillis()
        return ok
    }

    data class Output(
        val exit: Int,
        val output: String,
    )

    /**
     * Runs [command] as root, [stdin] streamed to it; null when `su` is missing or the command timed out. Output and
     * input go through their own threads: the timeout holds even when the command never ends nor closes its output.
     */
    fun run(
        command: String,
        stdin: InputStream? = null,
        timeoutS: Long = 120,
    ): Output? =
        try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader =
                Thread {
                    try {
                        val text = p.inputStream.bufferedReader().readText()
                        synchronized(out) { out.append(text) }
                    } catch (_: Exception) {
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            Thread {
                try {
                    p.outputStream.use { o -> stdin?.use { it.copyTo(o) } }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                p.destroy()
                reader.join(500)
                Log.w(TAG, "$command: timeout ${timeoutS}s")
                null
            } else {
                reader.join(2_000)
                Output(p.exitValue(), synchronized(out) { out.toString() }.trim())
            }
        } catch (e: Exception) {
            Log.w(TAG, "$command: ${e.message}")
            null
        }

    /** `pm install` fed through stdin: system_server cannot read the app's private files. */
    fun pmInstall(apk: File): Output? = run("pm install -r -d -S ${apk.length()}", apk.inputStream())
}
