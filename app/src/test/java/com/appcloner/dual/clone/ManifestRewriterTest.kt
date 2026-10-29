package com.appcloner.dual.clone

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestRewriterTest {
    @Test
    fun rewritesPackageLabelAuthorityAndKeepsClassName() {
        val sdk = sdkDir()
        val buildTools = File(sdk, "build-tools").listFiles().orEmpty()
            .filter { File(it, "aapt2").exists() }
            .maxByOrNull { it.name }
            ?: error("aapt2 not found under $sdk")
        val androidJar = File(sdk, "platforms").listFiles().orEmpty()
            .filter { File(it, "android.jar").exists() }
            .maxByOrNull { it.name }
            ?: error("android.jar not found")
        val work = File("build/rewriter-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val manifest = File(work, "AndroidManifest.xml")
        manifest.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.hello"
                android:sharedUserId="android.uid.system">
                <uses-permission android:name="android.permission.INTERNET" />
                <permission android:name="com.example.hello.PERM" android:protectionLevel="normal" />
                <application android:label="@string/app_name">
                    <activity android:name="com.example.hello.Main" android:exported="true" android:label="@string/app_name">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                    <activity android:name=".Settings" android:exported="false" android:label="Settings" />
                    <provider android:name=".P" android:authorities="com.example.hello.provider" android:exported="false" />
                </application>
            </manifest>
            """.trimIndent(),
        )
        val res = File(work, "res/values").apply { mkdirs() }
        File(res, "strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Hello</string>
            </resources>
            """.trimIndent(),
        )
        val compiled = File(work, "compiled.zip")
        run(File(buildTools, "aapt2"), "compile", "--dir", File(work, "res").path, "-o", compiled.path)
        val original = File(work, "original.apk")
        run(
            File(buildTools, "aapt2"),
            "link",
            "-o", original.path,
            "-I", File(androidJar, "android.jar").path,
            "--manifest", manifest.path,
            compiled.path,
            "--min-sdk-version", "24",
            "--target-sdk-version", "34",
        )
        val rewritten = File(work, "rewritten.apk")
        ApkRewriter.rewrite(original, rewritten, "com.example.hello", "c2.com.example.hello", "Hello 2")
        val signed = File(work, "signed.apk")
        File("src/main/assets/clone-signing.p12").inputStream().use { keystore ->
            ApkSignatures.sign(rewritten, signed, keystore, "cloneclone".toCharArray(), "clone", 24)
        }
        val badging = run(File(buildTools, "aapt"), "dump", "badging", signed.path)
        val tree = run(File(buildTools, "aapt"), "dump", "xmltree", signed.path, "AndroidManifest.xml")
        assertTrue(badging, badging.contains("name='c2.com.example.hello'"))
        assertTrue(badging, badging.contains("application-label:'Hello 2'"))
        assertTrue(badging, badging.contains("label='Hello 2'"))
        assertTrue(tree, tree.contains("\"com.example.hello.Main\""))
        assertTrue(tree, tree.contains("\"com.example.hello.Settings\""))
        assertTrue(tree, tree.contains("\"com.example.hello.P\""))
        assertFalse(tree, tree.contains("c2.com.example.hello.Main"))
        assertFalse(tree, tree.contains("\".Settings\""))
        assertFalse(tree, tree.contains("\".P\""))
        assertTrue(tree, tree.contains("\"c2.com.example.hello.provider\""))
        assertFalse(tree, tree.contains("\"com.example.hello.provider\""))
        assertTrue(tree, tree.contains("c2.com.example.hello.PERM"))
        assertTrue(tree, tree.contains("android.permission.INTERNET"))
        assertTrue(tree, tree.contains("Settings"))
        assertFalse(tree, tree.contains("sharedUserId"))
        assertFalse(tree, tree.contains("android.uid.system"))
        ZipFile(signed).use { zip ->
            val arsc = zip.getInputStream(zip.getEntry("resources.arsc")).use { it.readBytes() }
            val packageName = readArscPackage(arsc)
            assertEquals("c2.com.example.hello", packageName)
        }
        val arscOffset = dataOffset(signed, "resources.arsc")
        assertEquals(0, arscOffset % 4)
        run(File(buildTools, "apksigner"), "verify", "--min-sdk-version", "24", signed.path)
    }

    @Test
    fun stringPoolsRoundTrip() {
        for (utf8 in listOf(false, true)) {
            val original = ParsedPool(
                mutableListOf("label", "com.example.hello", "Hello", "héllo"),
                utf8,
                if (utf8) 0x100 else 0,
                IntArray(0),
                ByteArray(0),
            )
            val parsed = StringPool.parse(StringPool.build(original))
            assertEquals(original.strings, parsed.strings)
            assertEquals(utf8, parsed.utf8)
        }
    }

    @Test
    fun storedNativeLibraryIsAligned() {
        val work = File("build/rewriter-align").apply {
            deleteRecursively()
            mkdirs()
        }
        val input = File(work, "in.apk")
        val output = File(work, "out.apk")
        val payload = ByteArray(100) { 7 }
        ZipOutputStream(input.outputStream()).use { zip ->
            val entry = ZipEntry("lib/arm64-v8a/libx.so")
            entry.method = ZipEntry.STORED
            entry.size = payload.size.toLong()
            entry.compressedSize = payload.size.toLong()
            entry.crc = CRC32().apply { update(payload) }.value
            zip.putNextEntry(entry)
            zip.write(payload)
            zip.closeEntry()
            val dex = "dex".toByteArray()
            val dexEntry = ZipEntry("classes.dex")
            dexEntry.method = ZipEntry.DEFLATED
            zip.putNextEntry(dexEntry)
            zip.write(dex)
            zip.closeEntry()
        }
        AlignedZip.repack(input, output, emptyMap())
        assertEquals(0L, dataOffset(output, "lib/arm64-v8a/libx.so") % 16384)
        assertFalse(hasDataDescriptor(output))
    }

    private fun readArscPackage(arsc: ByteArray): String {
        var pos = u16(arsc, 2)
        val end = minOf(u32(arsc, 4), arsc.size)
        while (pos + 8 <= end) {
            val type = u16(arsc, pos)
            val size = u32(arsc, pos + 4)
            if (size < 8 || pos + size > arsc.size) break
            if (type == 0x0200 && size >= 12 + 256) {
                val chars = CharArray(128)
                var count = 0
                for (i in 0 until 128) {
                    val value = u16(arsc, pos + 12 + i * 2)
                    if (value == 0) break
                    chars[count++] = value.toChar()
                }
                val name = String(chars, 0, count)
                if (name.isNotEmpty() && name != "android") return name
            }
            pos += size
        }
        error("package chunk missing")
    }

    private fun dataOffset(file: File, entry: String): Long {
        val bytes = file.readBytes()
        var i = 0
        while (i + 30 < bytes.size) {
            if (u32(bytes, i) == 0x04034b50) {
                val flags = u16(bytes, i + 6)
                val nameLen = u16(bytes, i + 26)
                val extraLen = u16(bytes, i + 28)
                val name = bytes.copyOfRange(i + 30, i + 30 + nameLen).toString(Charsets.UTF_8)
                val data = i + 30L + nameLen + extraLen
                if (name == entry) {
                    if (flags and 0x8 != 0) error("$entry uses a data descriptor")
                    return data
                }
                val compressed = u32(bytes, i + 18).toLong() and 0xFFFFFFFFL
                i = (data + compressed).toInt()
            } else {
                i++
            }
        }
        error("missing $entry")
    }

    private fun hasDataDescriptor(file: File): Boolean {
        val bytes = file.readBytes()
        var i = 0
        while (i + 30 < bytes.size) {
            if (u32(bytes, i) == 0x04034b50) {
                if (u16(bytes, i + 6) and 0x8 != 0) return true
                val nameLen = u16(bytes, i + 26)
                val extraLen = u16(bytes, i + 28)
                val compressed = u32(bytes, i + 18).toLong() and 0xFFFFFFFFL
                i = (i + 30 + nameLen + extraLen + compressed).toInt()
            } else {
                i++
            }
        }
        return false
    }

    private fun sdkDir(): File {
        val props = listOf(File("local.properties"), File("../local.properties")).first { it.exists() }
        val line = props.readLines().first { it.startsWith("sdk.dir=") }
        return File(line.substringAfter("="))
    }

    private fun run(tool: File, vararg args: String): String {
        val process = ProcessBuilder(listOf(tool.path) + args)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) error("${tool.name} ${args.joinToString(" ")}\n$text")
        return text
    }
}
