package org.librehu.ota.install

import java.util.concurrent.TimeUnit

/**
 * LibreHU: the arguments of `su`, for every root of a head unit. Magisk, KernelSU, KernelSU Next, APatch and SuperSU take
 * `su -c COMMAND`. The old su of AOSP that some stock ROMs ship (Jancar's `/system/xbin/su` on some head units) is
 * `su [UID [COMMAND...]]`: it reads `-c` as a user and stops with "invalid uid/gid" before running anything, so it gets
 * `su 0 sh -c COMMAND`, a form KernelSU's su takes too (an app granted in its manager later still works). The form is
 * found once per process with `su -v`, which the others answer with their version without asking for root
 * (libsuperuser reads the version the same way) and the old su with that refusal.
 */
object Su {
    private const val REFUSAL = "invalid uid/gid"
    private const val PROBE_S = 3L

    /** True for the old su, null until `su -v` answered. */
    @Volatile
    private var old: Boolean? = null

    /** Arguments that run [command] as root, through `sh -c`. */
    fun argv(command: String): Array<String> =
        if (oldSu()) arrayOf("su", "0", "sh", "-c", command) else arrayOf("su", "-c", command)

    /**
     * True when [output] of a `su -c` call is the old su's refusal: the UID form is used from now on, and the command,
     * which did not run, can be run again with [argv].
     */
    fun refused(output: String): Boolean {
        if (old == true || !output.contains(REFUSAL, ignoreCase = true)) return false
        old = true
        return true
    }

    private fun oldSu(): Boolean {
        old?.let { return it }
        val found = probe() ?: return false
        old = found
        return found
    }

    /** True for the old su, false for the others; null when `su` cannot be run (not kept: root may come later). */
    private fun probe(): Boolean? =
        try {
            val p = ProcessBuilder("su", "-v").redirectErrorStream(true).start()
            p.outputStream.close()
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
            if (!p.waitFor(PROBE_S, TimeUnit.SECONDS)) p.destroy()
            reader.join(500)
            synchronized(out) { out.contains(REFUSAL, ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
}
