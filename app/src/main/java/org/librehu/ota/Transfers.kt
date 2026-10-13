package org.librehu.ota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.AppState
import org.librehu.ota.data.OtaSettingsStore
import org.librehu.ota.data.Task
import kotlin.math.abs

/**
 * Notifications of the downloads and installs, from the app or from the background check: one per app (progress,
 * speed, time left, pause / resume / cancel; then installed or failed), and while something runs a foreground
 * service with a summary, so that Android keeps the process (background install) until the end.
 */
object Transfers {
    private const val CHANNEL = "transfers"
    private const val CHANNEL_DONE = "transfers_done"
    const val SUMMARY_ID = 4300
    private const val GROUP = "transfers"

    /** Last notification of each app: what it said, when (downloads are refreshed at most every second). */
    private val shown = HashMap<String, Pair<String, Long>>()
    private var summaryAt = 0L

    /** Called on every change of the list (any thread). */
    @Synchronized
    fun update(
        context: Context,
        apps: List<AppState>,
    ) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        channels(app, nm)
        val enabled = OtaSettingsStore.value(app).notifyTransfers
        val running = apps.filter { it.task.running() }
        // Foreground service while something downloads or installs (keeps the process for a background install).
        if (running.isNotEmpty()) {
            if (!TransferService.started) {
                TransferService.started = true
                runCatching { app.startForegroundService(Intent(app, TransferService::class.java)) }
                    .onFailure { TransferService.started = false }
            } else if (SystemClock.elapsedRealtime() - summaryAt >= 1000) {
                summaryAt = SystemClock.elapsedRealtime()
                nm.notify(SUMMARY_ID, summary(app, running))
            }
        } else if (TransferService.started) {
            app.stopService(Intent(app, TransferService::class.java))
        }
        for (s in apps) {
            val pkg = s.app.packageName
            val key = key(s)
            val last = shown[pkg]
            if (!enabled || key == null) {
                if (last != null) {
                    shown.remove(pkg)
                    // A finished one stays until it is touched.
                    if (s.task.running() || s.task is Task.Paused || s.task == Task.Idle) nm.cancel(id(pkg))
                }
                continue
            }
            val now = SystemClock.elapsedRealtime()
            if (last != null && last.first == key) continue
            // Same state, new numbers: once a second.
            if (last != null && s.task is Task.Downloading && last.first.startsWith("dl") && now - last.second < 1000) continue
            shown[pkg] = key to now
            nm.notify(id(pkg), notification(app, s))
        }
    }

    /** What changes the notification: the task and, while downloading, its numbers. */
    private fun key(s: AppState): String? =
        when (val t = s.task) {
            is Task.Downloading -> "dl ${t.done} ${t.speed}"
            Task.Queued -> "queued"
            Task.Installing -> "installing"
            is Task.Paused -> "paused ${t.done}"
            Task.Done -> "done"
            Task.RebootNeeded -> "reboot"
            is Task.Failed -> "failed ${t.message}"
            is Task.Incompatible, is Task.Downgrade, is Task.SystemChoice -> "action"
            Task.Idle -> null
        }

    private fun notification(
        context: Context,
        s: AppState,
    ): Notification {
        val pkg = s.app.packageName
        val t = s.task
        val ongoing = t.running()
        val b =
            Notification
                .Builder(context, if (ongoing || t is Task.Paused) CHANNEL else CHANNEL_DONE)
                .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else R.drawable.ic_launcher_mono)
                .setContentTitle(s.app.name)
                .setContentIntent(open(context))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setShowWhen(!ongoing)
                .setGroup(GROUP)
                .setAutoCancel(!ongoing)
        when (t) {
            is Task.Downloading -> {
                b.setContentText(progressText(context, t.progress, t.done, t.total, t.speed, t.etaS))
                if (t.total > 0) b.setProgress(1000, (t.progress * 1000).toInt(), false) else b.setProgress(0, 0, true)
                b.addAction(action(context, ACTION_PAUSE, pkg, R.string.tr_pause))
                b.addAction(action(context, ACTION_CANCEL, pkg, android.R.string.cancel))
            }

            Task.Queued -> {
                b.setContentText(context.getString(R.string.queued)).setProgress(0, 0, true)
            }

            Task.Installing -> {
                b.setContentText(context.getString(R.string.tr_installing)).setProgress(0, 0, true)
            }

            is Task.Paused -> {
                b.setContentText(context.getString(R.string.tr_paused, percent(t.progress), size(context, t.done), size(context, t.total)))
                if (t.total > 0) b.setProgress(1000, (t.progress * 1000).toInt(), false)
                b.addAction(action(context, ACTION_RESUME, pkg, R.string.tr_resume))
                b.addAction(action(context, ACTION_CANCEL, pkg, android.R.string.cancel))
            }

            Task.Done -> {
                b.setContentText(context.getString(R.string.tr_done, s.installedName ?: ""))
            }

            Task.RebootNeeded -> {
                b.setContentText(context.getString(R.string.reboot_needed))
            }

            is Task.Failed -> {
                b.setContentText(context.getString(R.string.install_failed, t.message))
            }

            else -> {
                b.setContentText(context.getString(R.string.tr_action))
            }
        }
        return b.build()
    }

    /** Foreground notification: how many run, and their total progress. */
    fun summary(
        context: Context,
        running: List<AppState>,
    ): Notification {
        val downloads = running.mapNotNull { it.task as? Task.Downloading }
        val done = downloads.sumOf { it.done }
        val total = downloads.takeIf { d -> d.isNotEmpty() && d.all { it.total > 0 } }?.sumOf { it.total } ?: -1L
        val speed = downloads.sumOf { it.speed }
        val b =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.resources.getQuantityString(R.plurals.tr_summary, running.size, running.size))
                .setContentText(running.joinToString(", ") { it.app.name })
                .setContentIntent(open(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP)
                .setGroupSummary(true)
        if (downloads.isNotEmpty()) {
            val progress = if (total > 0) done.toFloat() / total else -1f
            val eta = if (total > 0 && speed > 0) (total - done) / speed else -1L
            b.setSubText(progressText(context, progress, done, total, speed, eta))
            if (total > 0) b.setProgress(1000, (progress * 1000).toInt(), false) else b.setProgress(0, 0, true)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    /** 45 % · 3.2 / 7.0 MB · 1.4 MB/s · 0:03 left. */
    fun progressText(
        context: Context,
        progress: Float,
        done: Long,
        total: Long,
        speed: Long,
        etaS: Long,
    ): String =
        listOfNotNull(
            percent(progress).takeIf { progress >= 0 },
            if (total > 0) "${size(context, done)} / ${size(context, total)}" else size(context, done),
            if (speed > 0) "${size(context, speed)}/s" else null,
            if (etaS >= 0) context.getString(R.string.tr_eta, duration(etaS)) else null,
        ).joinToString(" · ")

    fun percent(p: Float) = if (p < 0) "" else "${(p * 100).toInt()} %"

    /** Size in the language of the device (Mo, MB…). */
    fun size(
        context: Context,
        bytes: Long,
    ): String =
        if (bytes < 0) {
            "?"
        } else {
            android.text.format.Formatter
                .formatShortFileSize(context, bytes)
        }

    fun duration(s: Long): String =
        if (s >=
            3600
        ) {
            "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        } else {
            "%d:%02d".format(s / 60, s % 60)
        }

    private fun Task.running() = this is Task.Queued || this is Task.Downloading || this == Task.Installing

    private fun id(pkg: String) = 4400 + abs(pkg.hashCode() % 5000)

    private fun open(context: Context) =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun action(
        context: Context,
        what: String,
        pkg: String,
        label: Int,
    ): Notification.Action =
        Notification.Action
            .Builder(
                null as android.graphics.drawable.Icon?,
                context.getString(label),
                PendingIntent.getBroadcast(
                    context,
                    (what + pkg).hashCode(),
                    Intent(context, TransferActionReceiver::class.java).setAction(what).putExtra(EXTRA_PKG, pkg),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()

    private fun channels(
        context: Context,
        nm: NotificationManager,
    ) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, context.getString(R.string.channel_transfers_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    const val ACTION_PAUSE = "org.librehu.ota.PAUSE"
    const val ACTION_RESUME = "org.librehu.ota.RESUME"
    const val ACTION_CANCEL = "org.librehu.ota.CANCEL"
    const val EXTRA_PKG = "pkg"
}

/** Pause, resume and cancel from the notifications. */
class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pkg = intent.getStringExtra(Transfers.EXTRA_PKG) ?: return
        val repo = AppRepository.get(context)
        when (intent.action) {
            Transfers.ACTION_PAUSE -> repo.pause(pkg)
            Transfers.ACTION_RESUME -> repo.resume(pkg)
            Transfers.ACTION_CANCEL -> repo.cancel(pkg)
        }
    }
}

/** Foreground while downloads or installs run (see [Transfers]); the list keeps its notification up to date. */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        started = true
        val running =
            AppRepository
                .get(this)
                .apps.value
                .filter { it.task is Task.Downloading || it.task == Task.Queued || it.task == Task.Installing }
        val n = Transfers.summary(this, running)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Transfers.SUMMARY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Transfers.SUMMARY_ID, n)
        }
        if (running.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var started = false
    }
}
