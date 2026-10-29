package com.appcloner.dual.clone

import com.android.apksig.ApkSigner
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

internal object ApkSignatures {
    fun sign(input: File, output: File, keystore: InputStream, password: CharArray, alias: String, minSdk: Int) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(keystore, password)
        val key = ks.getKey(alias, password) as PrivateKey
        val certs = ks.getCertificateChain(alias).map { it as X509Certificate }
        val signer = ApkSigner.SignerConfig.Builder(alias, key, certs).build()
        output.parentFile?.mkdirs()
        val builder = ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setAlignmentPreserved(true)
        try {
            builder.build().sign()
        } catch (e: Exception) {
            output.delete()
            throw IllegalStateException("Couldn't sign the clone.", e)
        }
    }
}
