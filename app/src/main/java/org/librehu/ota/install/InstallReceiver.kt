package org.librehu.ota.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.librehu.ota.data.AppRepository

/** Result of an Android installer session ([Installer.install] without root). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val repo = AppRepository.get(context)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    }
}
