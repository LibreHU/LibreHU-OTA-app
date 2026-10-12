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
import java.util.concurrent.TimeUnit

/** Daily check for new releases (with network); a notification lists the apps to update. */
class CheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val repo = AppRepository.get(this)
        CoroutineScope(Dispatchers.IO).launch {
            repo.check()
            delay(500)
            repo.busy.first { !it }
            notifyUpdates(this@CheckJob, repo.updates().map { it.app.name })
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 4201
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 1
        private const val PREF_AUTO = "auto_check"

        fun enabled(context: Context) = context.getSharedPreferences("ota", Context.MODE_PRIVATE).getBoolean(PREF_AUTO, true)

        fun setEnabled(
            context: Context,
            on: Boolean,
        ) {
            context
                .getSharedPreferences("ota", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_AUTO, on)
                .apply()
            schedule(context)
        }

        /** Schedules (or cancels) the daily check. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled(context)) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, CheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.HOURS.toMillis(24))
                    .setPersisted(true)
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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) CheckJob.schedule(context)
    }
}
