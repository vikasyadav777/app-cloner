package com.appcloner.dual.clone

import android.content.pm.PackageManager
import android.os.Build

internal object CloneNames {
    fun next(
        pm: PackageManager,
        sourcePackage: String,
        reservedPackages: Set<String>,
    ): Pair<String, Int> {
        for (n in 2..99) {
            val pkg = packageFor(sourcePackage, n)
            if (pkg in reservedPackages) continue
            if (pkg.length >= 128) continue
            if (pkg in reservedPackages || isInstalled(pm, pkg)) continue
            return pkg to n
        }
        throw IllegalStateException("This app already has as many clones as Clone Space can install.")
    }

    private fun packageFor(sourcePackage: String, number: Int): String {
        val full = "c$number.$sourcePackage"
        return if (full.length < 128) full else "c$number.x${hash(sourcePackage)}"
    }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun hash(sourcePackage: String): String {
        return sourcePackage.hashCode().toUInt().toString(16).padStart(8, '0')
    }
}
