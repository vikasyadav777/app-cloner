package com.appcloner.dual.clone

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Build
import java.io.File

internal object CloneInstaller {
    fun install(
        context: Context,
        apks: List<File>,
        clonePackage: String,
        label: String,
        sourcePackage: String,
    ) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(clonePackage)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        params.setSize(apks.sumOf { it.length() })
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            } catch (_: Exception) {
                // The system still shows its install confirmation.
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apks.forEachIndexed { index, file ->
                session.openWrite("part$index", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            session.commit(callback(context, InstallContract.OP_INSTALL, clonePackage, label, sourcePackage).intentSender)
        }
    }

    fun uninstall(context: Context, clonePackage: String, label: String, sourcePackage: String) {
        context.packageManager.packageInstaller.uninstall(
            VersionedPackage(clonePackage, PackageManager.VERSION_CODE_HIGHEST.toLong()),
            callback(context, InstallContract.OP_UNINSTALL, clonePackage, label, sourcePackage).intentSender,
        )
    }

    private fun callback(
        context: Context,
        op: String,
        clonePackage: String,
        label: String,
        sourcePackage: String,
    ): PendingIntent {
        val intent = Intent(context, InstallResultActivity::class.java).apply {
            putExtra(InstallContract.EXTRA_OP, op)
            putExtra(InstallContract.EXTRA_CLONE_PACKAGE, clonePackage)
            putExtra(InstallContract.EXTRA_LABEL, label)
            putExtra(InstallContract.EXTRA_SOURCE, sourcePackage)
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
        } else {
            null
        }
        return PendingIntent.getActivity(context, clonePackage.hashCode(), intent, flags, options)
    }
}
