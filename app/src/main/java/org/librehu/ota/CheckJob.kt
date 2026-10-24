package org.librehu.ota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.OtaSettingsStore
import java.util.concurrent.TimeUnit

/** Daily check for new releases (with network); a notification lists the apps to update. */
class CheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val repo = AppRepository.get(this)
        CoroutineScope(Dispatchers.IO).launch {
            // Finished whatever happens: a job never finished holds its wakelock until Android stops it.
            try {
                repo.check()
                delay(500)
                repo.busy.first { !it }
                val s = OtaSettingsStore.value(this@CheckJob)
                val updates = repo.updates().filter { it.app.packageName !in s.ignored }
                if (s.notify) notifyUpdates(this@CheckJob, updates.map { it.app.name })
                // Background install (root only, so that nothing asks): LibreHU Apps itself last.
                if (s.autoInstall && updates.isNotEmpty() &&
                    org.librehu.ota.install.Root
                        .isAvailable()
                ) {
                    repo.updateAll()
                }
            } catch (e: Exception) {
                android.util.Log.w("LibreHU-OTA", "check: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 4201
        private const val JOB_ID_BOOT = 4202
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 1

        fun enabled(context: Context) = OtaSettingsStore.value(context).autoCheck

        fun setEnabled(
            context: Context,
            on: Boolean,
        ) {
            OtaSettingsStore.update(context) { it.copy(autoCheck = on) }
            schedule(context, force = true)
        }

        /** Schedules (or cancels) the periodic check, at the interval and on the network of the settings. */
        fun schedule(
            context: Context,
            force: Boolean = false,
        ) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val s = OtaSettingsStore.value(context)
            if (!s.autoCheck) {
                js.cancel(JOB_ID)
                return
            }
            if (!force && js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, CheckJob::class.java))
                    .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.HOURS.toMillis(s.intervalH.coerceAtLeast(1).toLong()))
                    .setPersisted(true)
                    .build(),
            )
        }

        /** One check when a network is there (after a start); the list is kept for when there is none. */
        fun scheduleOnce(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            js.schedule(
                JobInfo
                    .Builder(JOB_ID_BOOT, ComponentName(context, CheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }

        fun notifyUpdates(
            context: Context,
            names: List<String>,
        ) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (names.isEmpty()) {
                nm.cancel(NOTIFICATION_ID)
                return
            }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            nm.notify(
                NOTIFICATION_ID,
                Notification
                    .Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_mono)
                    .setContentTitle(context.resources.getQuantityString(R.plurals.updates_title, names.size, names.size))
                    .setContentText(names.joinToString(", "))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}

/** Schedules the daily check after a reboot (the job is persisted, this covers a first install). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CheckJob.schedule(context)
            // A fresh list after each start, as soon as there is network.
            CheckJob.scheduleOnce(context)
            // Native code for the apps not compiled yet (without LibreHU-service, which does it itself).
            org.librehu.ota.install.CompileJob
                .schedule(context)
        }
    }
}
