package org.librehu.ota.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import org.librehu.ota.data.CatalogApp
import java.io.File
import java.security.MessageDigest

/**
 * Signing certificates (SHA-256 of each) of an APK before it is installed. Android already refuses an update signed
 * with another key, not a first install: an APK of a LibreHU repository must carry the LibreHU key, another one the
 * key named by the catalogue (`cert`), and an APK from a USB key the LibreHU key or the one of the installed app.
 */
object Signatures {
    /** Certificate of keystore/librehu.jks (alias librehu), the key of every LibreHU app. */
    const val LIBREHU = "91a1e7a1ca2c51dacbfd638a1507187b40d9400394d2902a3215f610d3fc00dc"

    sealed class Check {
        /** Signed with an expected key; [librehu]: the LibreHU key. */
        data class Ok(
            val librehu: Boolean,
        ) : Check()

        /** Nothing to compare with (app outside LibreHU without `cert` in the catalogue). */
        data object Unchecked : Check()

        /** Another key, or none readable. */
        data class Refused(
            val found: String,
        ) : Check()
    }

    @Suppress("DEPRECATION")
    fun ofArchive(
        context: Context,
        apk: File,
    ): Set<String> {
        val pm = context.packageManager
        val signers =
            runCatching { pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: runCatching { pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.signatures }.getOrNull()
        return digests(signers)
    }

    @Suppress("DEPRECATION")
    fun ofInstalled(
        context: Context,
        pkg: String,
    ): Set<String> {
        val info: PackageInfo =
            try {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } catch (_: PackageManager.NameNotFoundException) {
                return emptySet()
            }
        val s = info.signingInfo
        return digests(if (s == null) null else if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory)
    }

    /** Keys a catalogue app must be signed with; empty: not checked. */
    fun expected(app: CatalogApp?): Set<String> =
        when {
            app == null -> emptySet()
            app.cert.isNotBlank() -> app.cert.split(',').map { it.trim().replace(":", "").lowercase() }.filter { it.isNotEmpty() }.toSet()
            app.repo.startsWith("LibreHU/") -> setOf(LIBREHU)
            else -> emptySet()
        }

    /** [apk] of a catalogue [app] (download). */
    fun check(
        context: Context,
        apk: File,
        app: CatalogApp?,
    ): Check {
        val certs = ofArchive(context, apk)
        if (certs.isEmpty()) return Check.Refused("?")
        val expected = expected(app)
        if (expected.isEmpty()) return Check.Unchecked
        return if (certs.all { it in expected }) Check.Ok(LIBREHU in certs) else Check.Refused(short(certs))
    }

    /** [apk] from a USB key: the LibreHU key, or the key of the app [pkg] already installed. */
    fun checkLocal(
        context: Context,
        apk: File,
        pkg: String,
    ): Check {
        val certs = ofArchive(context, apk)
        if (certs.isEmpty()) return Check.Refused("?")
        if (certs == setOf(LIBREHU)) return Check.Ok(true)
        val installed = ofInstalled(context, pkg)
        return if (installed.isNotEmpty() && certs.all { it in installed }) Check.Ok(false) else Check.Refused(short(certs))
    }

    /** The first 16 hex digits, as LibreHU's checks print them. */
    fun short(certs: Set<String>) = certs.joinToString(", ") { it.take(16) }

    private fun digests(signers: Array<Signature>?): Set<String> =
        signers
            .orEmpty()
            .map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }
            .toSet()
}
