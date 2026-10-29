package com.appcloner.dual.clone

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Rewrites an APK zip without data descriptors. resources.arsc is stored and
 * 4-byte aligned, and stored native libraries are 16 KB aligned, which Android's
 * package installer requires.
 */
internal object AlignedZip {
    private const val LOCAL = 0x04034b50L
    private const val CENTRAL = 0x02014b50L
    private const val EOCD = 0x06054b50L
    private const val ALIGN_ARSC = 4
    private const val ALIGN_SO = 16384

    fun repack(input: File, output: File, replacements: Map<String, ByteArray>) {
        output.parentFile?.mkdirs()
        ZipFile(input).use { zip ->
            BufferedOutputStream(FileOutputStream(output)).use { buffered ->
                val out = CountingOutputStream(buffered)
                val central = ArrayList<Central>()
                val seen = HashSet<String>()
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (!seen.add(name) || name.endsWith("/")) continue
                    if (isSignature(name)) continue
                    val replacement = replacements[name]
                    val method = methodFor(name, entry, replacement != null)
                    val align = alignFor(name, method)
                    val payload = payloadFor(zip, entry, replacement, method, output.parentFile ?: output)
                    try {
                        val extra = extraForAlignment(out.count, name.toByteArray(Charsets.UTF_8).size, align)
                        val offset = out.count
                        writeLocal(out, name, method, payload.crc, payload.compressedSize, payload.size, extra)
                        when (val source = payload.source) {
                            is Source.Bytes -> out.write(source.bytes)
                            is Source.File -> source.file.inputStream().use { it.copyTo(out) }
                            is Source.Stored -> source.zip.getInputStream(source.entry).use { it.copyTo(out) }
                        }
                        central += Central(name, method, payload.crc, payload.compressedSize, payload.size, extra, offset)
                    } finally {
                        payload.cleanup()
                    }
                }
                if (central.isEmpty()) throw IllegalArgumentException("This app has no files to clone.")
                if (central.size > 0xFFFF) throw IllegalArgumentException("This app has too many files to clone.")
                val cdOffset = out.count
                for (item in central) writeCentral(out, item)
                val cdSize = out.count - cdOffset
                writeEocd(out, central.size, cdSize, cdOffset)
            }
        }
    }

    private fun methodFor(name: String, entry: ZipEntry, replaced: Boolean): Int {
        if (name == "resources.arsc") return ZipEntry.STORED
        if (replaced && name == "AndroidManifest.xml") return ZipEntry.DEFLATED
        return if (entry.method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
    }

    private fun alignFor(name: String, method: Int): Int {
        if (method != ZipEntry.STORED) return 1
        return when {
            name == "resources.arsc" -> ALIGN_ARSC
            name.endsWith(".so") -> ALIGN_SO
            else -> 1
        }
    }

    private fun payloadFor(
        zip: ZipFile,
        entry: ZipEntry,
        replacement: ByteArray?,
        method: Int,
        tempDir: File,
    ): Payload {
        if (replacement != null && method == ZipEntry.STORED) {
            return Payload(crc32(replacement), replacement.size.toLong(), replacement.size.toLong(), Source.Bytes(replacement))
        }
        if (replacement != null) {
            val tmp = File.createTempFile("clone-zip", ".bin", tempDir)
            val (crc, size) = deflateTo(replacement.inputStream(), tmp)
            return Payload(crc, tmp.length(), size, Source.File(tmp))
        }
        if (method == ZipEntry.STORED) {
            val (crc, size) = zip.getInputStream(entry).use { crcOf(it) }
            return Payload(crc, size, size, Source.Stored(zip, entry))
        }
        val tmp = File.createTempFile("clone-zip", ".bin", tempDir)
        val (crc, size) = zip.getInputStream(entry).use { deflateTo(it, tmp) }
        return Payload(crc, tmp.length(), size, Source.File(tmp))
    }

    private fun crcOf(input: InputStream): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        val buf = ByteArray(65536)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            crc.update(buf, 0, n)
            size += n
        }
        return crc.value to size
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    private fun deflateTo(input: InputStream, tmp: File): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            FileOutputStream(tmp).use { fos ->
                DeflaterOutputStream(fos, deflater).use { defOut ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                        size += n
                        defOut.write(buf, 0, n)
                    }
                }
            }
        } finally {
            deflater.end()
        }
        return crc.value to size
    }

    private fun extraForAlignment(offset: Long, nameLen: Int, align: Int): ByteArray {
        if (align <= 1) return ByteArray(0)
        val base = offset + 30 + nameLen + 4
        val payload = ((align - (base % align)) % align).toInt()
        val extra = ByteArray(4 + payload)
        extra[0] = 0x1E
        extra[1] = 0xA1.toByte()
        extra[2] = (payload and 0xFF).toByte()
        extra[3] = ((payload ushr 8) and 0xFF).toByte()
        return extra
    }

    private fun writeLocal(
        out: CountingOutputStream,
        name: String,
        method: Int,
        crc: Long,
        compressed: Long,
        size: Long,
        extra: ByteArray,
    ) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        writeU32(out, LOCAL)
        writeU16(out, if (method == ZipEntry.DEFLATED) 20 else 10)
        writeU16(out, 0x0800)
        writeU16(out, method)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU32(out, crc)
        writeU32(out, compressed)
        writeU32(out, size)
        writeU16(out, bytes.size)
        writeU16(out, extra.size)
        out.write(bytes)
        out.write(extra)
    }

    private fun writeCentral(out: CountingOutputStream, item: Central) {
        val bytes = item.name.toByteArray(Charsets.UTF_8)
        writeU32(out, CENTRAL)
        writeU16(out, 20)
        writeU16(out, if (item.method == ZipEntry.DEFLATED) 20 else 10)
        writeU16(out, 0x0800)
        writeU16(out, item.method)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU32(out, item.crc)
        writeU32(out, item.compressed)
        writeU32(out, item.size)
        writeU16(out, bytes.size)
        writeU16(out, item.extra.size)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU32(out, 0)
        writeU32(out, item.offset)
        out.write(bytes)
        out.write(item.extra)
    }

    private fun writeEocd(out: CountingOutputStream, count: Int, cdSize: Long, cdOffset: Long) {
        writeU32(out, EOCD)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, count)
        writeU16(out, count)
        writeU32(out, cdSize)
        writeU32(out, cdOffset)
        writeU16(out, 0)
    }

    private fun writeU16(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: OutputStream, value: Long) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw IllegalArgumentException("This app is too large to clone.")
        }
        val v = value.toInt()
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun isSignature(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val file = name.substringAfterLast('/')
        return file.equals("MANIFEST.MF", true) ||
            file.endsWith(".SF", true) ||
            file.endsWith(".RSA", true) ||
            file.endsWith(".DSA", true) ||
            file.endsWith(".EC", true) ||
            file.startsWith("SIG-", true)
    }

    private class Payload(
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val source: Source,
    ) {
        fun cleanup() {
            if (source is Source.File) source.file.delete()
        }
    }

    private sealed class Source {
        class Bytes(val bytes: ByteArray) : Source()
        class File(val file: java.io.File) : Source()
        class Stored(val zip: ZipFile, val entry: ZipEntry) : Source()
    }

    private class Central(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressed: Long,
        val size: Long,
        val extra: ByteArray,
        val offset: Long,
    )

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
