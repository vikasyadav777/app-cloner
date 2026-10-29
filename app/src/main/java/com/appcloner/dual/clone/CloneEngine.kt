package com.appcloner.dual.clone

import android.content.Context
import java.io.File

internal object CloneEngine {
    fun build(
        context: Context,
        directory: File,
        sourcePackage: String,
        newPackage: String,
        label: String,
        onProgress: (String) -> Unit,
    ): List<File> {
        val info = context.packageManager.getApplicationInfo(sourcePackage, 0)
        val paths = buildList {
            val base = info.sourceDir ?: throw IllegalStateException("Android wouldn't let Clone Space read this app.")
            add(base)
            info.splitSourceDirs?.forEach { add(it) }
        }
        directory.mkdirs()
        val minSdk = info.minSdkVersion.coerceAtLeast(1)
        return paths.mapIndexed { index, path ->
            val input = File(path)
            if (!input.canRead()) {
                throw IllegalStateException("Android wouldn't let Clone Space read this app.")
            }
            onProgress("Packaging ${index + 1} of ${paths.size}…")
            val unsigned = File(directory, "unsigned-$index.apk")
            val signed = File(directory, "signed-$index.apk")
            ApkRewriter.rewrite(input, unsigned, sourcePackage, newPackage, label)
            onProgress("Signing $label…")
            context.assets.open(SigningMaterial.ASSET).use { keystore ->
                ApkSignatures.sign(
                    unsigned,
                    signed,
                    keystore,
                    SigningMaterial.PASSWORD.toCharArray(),
                    SigningMaterial.ALIAS,
                    minSdk,
                )
            }
            unsigned.delete()
            signed
        }
    }
}
