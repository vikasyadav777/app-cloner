package com.appcloner.dual.clone

import java.io.ByteArrayOutputStream

internal class ParsedPool(
    val strings: MutableList<String>,
    val utf8: Boolean,
    val flags: Int,
    val styleOffsets: IntArray,
    val styleBytes: ByteArray,
)

internal object StringPool {
    fun parse(chunk: ByteArray): ParsedPool {
        require(chunk.size >= 28 && u16(chunk, 0) == 0x0001) { "Bad string pool" }
        val headerSize = u16(chunk, 2)
        val chunkSize = u32(chunk, 4)
        val stringCount = u32(chunk, 8)
        val styleCount = u32(chunk, 12)
        val flags = u32(chunk, 16)
        val stringsStart = u32(chunk, 20)
        val stylesStart = u32(chunk, 24)
        val utf8 = flags and 0x100 != 0
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val off = u32(chunk, headerSize + i * 4)
            val at = stringsStart + off
            strings += if (utf8) decodeUtf8(chunk, at) else decodeUtf16(chunk, at)
        }
        val styleOffsets = IntArray(styleCount) { i ->
            u32(chunk, headerSize + stringCount * 4 + i * 4)
        }
        val styleBytes = if (styleCount == 0 || stylesStart <= 0) {
            ByteArray(0)
        } else {
            chunk.copyOfRange(stylesStart, minOf(chunkSize, chunk.size))
        }
        return ParsedPool(strings, utf8, flags, styleOffsets, styleBytes)
    }

    fun build(pool: ParsedPool): ByteArray {
        val strings = pool.strings
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            offsets[i] = data.size()
            if (pool.utf8) writeUtf8(data, strings[i]) else writeUtf16(data, strings[i])
        }
        while (data.size() % 4 != 0) data.write(0)
        val styleOffsets = pool.styleOffsets
        val stringsStart = 28 + strings.size * 4 + styleOffsets.size * 4
        val stylesStart = if (styleOffsets.isEmpty()) 0 else stringsStart + data.size()
        val styleBytes = pool.styleBytes
        val total = stringsStart + data.size() + styleBytes.size
        val padded = (total + 3) and 3.inv()
        val out = ByteArray(padded)
        putU16(out, 0, 0x0001)
        putU16(out, 2, 28)
        putU32(out, 4, padded)
        putU32(out, 8, strings.size)
        putU32(out, 12, styleOffsets.size)
        var flags = pool.flags and 1.inv()
        flags = if (pool.utf8) flags or 0x100 else flags and 0x100.inv()
        putU32(out, 16, flags)
        putU32(out, 20, stringsStart)
        putU32(out, 24, stylesStart)
        var cursor = 28
        for (off in offsets) {
            putU32(out, cursor, off)
            cursor += 4
        }
        for (off in styleOffsets) {
            putU32(out, cursor, off)
            cursor += 4
        }
        data.toByteArray().copyInto(out, stringsStart)
        if (styleBytes.isNotEmpty()) styleBytes.copyInto(out, stylesStart)
        return out
    }

    private fun decodeUtf16(buf: ByteArray, start: Int): String {
        var o = start
        val first = u16(buf, o)
        val charLen: Int
        if (first and 0x8000 != 0) {
            val second = u16(buf, o + 2)
            charLen = ((first and 0x7FFF) shl 16) or second
            o += 4
        } else {
            charLen = first
            o += 2
        }
        val chars = CharArray(charLen)
        for (i in 0 until charLen) chars[i] = u16(buf, o + i * 2).toChar()
        return String(chars)
    }

    private fun decodeUtf8(buf: ByteArray, start: Int): String {
        val (_, afterChars) = decodeLen8(buf, start)
        val (byteLen, dataAt) = decodeLen8(buf, afterChars)
        return buf.copyOfRange(dataAt, dataAt + byteLen).toString(Charsets.UTF_8)
    }

    private fun decodeLen8(buf: ByteArray, o: Int): Pair<Int, Int> {
        val b = buf[o].toInt() and 0xFF
        return if (b and 0x80 != 0) {
            val b2 = buf[o + 1].toInt() and 0xFF
            (((b and 0x7F) shl 8) or b2) to (o + 2)
        } else {
            b to (o + 1)
        }
    }

    private fun writeUtf16(out: ByteArrayOutputStream, value: String) {
        val len = value.length
        if (len > 0x7FFF) {
            writeU16(out, (len ushr 16) or 0x8000)
            writeU16(out, len and 0xFFFF)
        } else {
            writeU16(out, len)
        }
        for (ch in value) writeU16(out, ch.code)
        writeU16(out, 0)
    }

    private fun writeUtf8(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeLen8(out, value.length)
        writeLen8(out, bytes.size)
        out.write(bytes)
        out.write(0)
    }

    private fun writeLen8(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) {
            out.write(((len shr 8) and 0x7F) or 0x80)
            out.write(len and 0xFF)
        } else {
            out.write(len)
        }
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }
}
