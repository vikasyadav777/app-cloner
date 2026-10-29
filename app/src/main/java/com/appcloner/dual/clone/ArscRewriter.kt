package com.appcloner.dual.clone

internal object ArscRewriter {
    fun rewrite(arsc: ByteArray, oldPackage: String, newPackage: String): ByteArray {
        if (arsc.size < 12 || u16(arsc, 0) != 0x0002) return arsc
        if (newPackage.length >= 128) {
            throw IllegalArgumentException("The clone package name is too long.")
        }
        val copy = arsc.copyOf()
        val end = minOf(u32(copy, 4), copy.size)
        var pos = u16(copy, 2)
        while (pos + 8 <= end) {
            val type = u16(copy, pos)
            val size = u32(copy, pos + 4)
            if (size < 8 || pos + size > copy.size) break
            if (type == 0x0200 && size >= 12 + 256) {
                val name = readPackageName(copy, pos + 12)
                if (name == oldPackage) writePackageName(copy, pos + 12, newPackage)
            }
            pos += size
        }
        return copy
    }

    private fun readPackageName(bytes: ByteArray, offset: Int): String {
        val chars = CharArray(128)
        var count = 0
        for (i in 0 until 128) {
            val value = u16(bytes, offset + i * 2)
            if (value == 0) break
            chars[count++] = value.toChar()
        }
        return String(chars, 0, count)
    }

    private fun writePackageName(bytes: ByteArray, offset: Int, name: String) {
        for (i in 0 until 128) {
            val value = if (i < name.length) name[i].code else 0
            putU16(bytes, offset + i * 2, value)
        }
    }
}
