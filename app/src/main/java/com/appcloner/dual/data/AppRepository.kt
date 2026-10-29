package com.appcloner.dual.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas

object AppRepository {
    fun load(context: Context): List<PhoneApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launcher, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved.mapNotNull { resolve ->
            val info = resolve.activityInfo?.applicationInfo ?: return@mapNotNull null
            val packageName = info.packageName
            if (packageName == context.packageName) return@mapNotNull null
            val label = resolve.loadLabel(pm)?.toString().orEmpty().ifBlank { packageName }
            val versionCode = try {
                pm.getPackageInfo(packageName, 0).longVersionCode
            } catch (_: PackageManager.NameNotFoundException) {
                0L
            }
            PhoneApp(
                packageName = packageName,
                label = label,
                system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                versionCode = versionCode,
                icon = drawIcon(resolve.loadIcon(pm)),
            )
        }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun drawIcon(drawable: android.graphics.drawable.Drawable): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, 128, 128)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
