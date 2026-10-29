package com.appcloner.dual.clone

import java.io.File
import java.util.zip.ZipFile

internal object ApkRewriter {
    fun rewrite(input: File, output: File, oldPackage: String, newPackage: String, label: String) {
        val edits = HashMap<String, ByteArray>()
        ZipFile(input).use { zip ->
            zip.getEntry("AndroidManifest.xml")?.let { entry ->
                val xml = zip.getInputStream(entry).use { it.readBytes() }
                edits["AndroidManifest.xml"] = ManifestRewriter.rewrite(xml, oldPackage, newPackage, label)
            }
            zip.getEntry("resources.arsc")?.let { entry ->
                val arsc = zip.getInputStream(entry).use { it.readBytes() }
                edits["resources.arsc"] = ArscRewriter.rewrite(arsc, oldPackage, newPackage)
            }
        }
        AlignedZip.repack(input, output, edits)
    }
}
