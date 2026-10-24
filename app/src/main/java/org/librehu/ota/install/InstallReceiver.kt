package org.librehu.ota.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository

/** Result of an Android installer session ([Installer.install] without root). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val repo = AppRepository.get(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) cancelConfirm(context, pkg)
        when (status ) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Android 10+ does not let an app in the background open a screen: the confirmation also comes as a
                // notification (opened by a touch). Up to Android 9, and with LibreHU Apps on screen, it opens itself.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) notifyConfirm(context, pkg, confirm)
                runCatching { context.startActivity(confirm) }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                repo.onInstallResult(pkg, InstallResult.Done)
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                repo.onInstallResult(
                    pkg,
                    if (message.contains("VERSION_DOWNGRADE")) {
                        InstallResult.Downgrade
                    } else if (message.contains("UPDATE_INCOMPATIBLE") || message.contains("signatures")) {
                        InstallResult.Incompatible
                    } else {
                        InstallResult.Failed(message.ifEmpty { "status $status" })
                    },
                )
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                repo.onInstallResult(pkg, InstallResult.Failed(""))
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                repo.onInstallResult(
                    pkg,
                    if (message.contains(
                            "VERSION_DOWNGRADE",
                        )
                    ) {
                        InstallResult.Downgrade
                    } else {
                        InstallResult.Failed(message.ifEmpty { "status $status" })
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "org.librehu.ota.PACKAGE"
        private const val CHANNEL = "install_confirm"

        private fun id(pkg: String) = 0x4F00 + (pkg.hashCode() and 0xFF)

        private fun notifyConfirm(
            context: Context,
            pkg: String,
            confirm: Intent,
        ) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_install_confirm), NotificationManager.IMPORTANCE_HIGH),
            )
            val name = AppRepository.get(context).apps.value.firstOrNull { it.app.packageName == pkg }?.app?.name ?: pkg
            val open = PendingIntent.getActivity(context, id(pkg), confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                id(pkg),
                Notification
                    .Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_mono)
                    .setContentTitle(context.getString(R.string.install_confirm_title, name))
                    .setContentText(context.getString(R.string.install_confirm_text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        private fun cancelConfirm(
            context: Context,
            pkg: String,
        ) {
            context.getSystemService(NotificationManager::class.java)?.cancel(id(pkg))
        }
    }
}
