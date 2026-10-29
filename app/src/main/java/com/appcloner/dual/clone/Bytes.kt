package com.appcloner.dual.clone

internal fun u16(b: ByteArray, o: Int): Int {
    return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}

internal fun u32(b: ByteArray, o: Int): Int {
    return (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)
}

internal fun putU16(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xFF).toByte()
    b[o + 1] = ((v ushr 8) and 0xFF).toByte()
}

internal fun putU32(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xFF).toByte()
    b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    b[o + 2] = ((v ushr 16) and 0xFF).toByte()
    b[o + 3] = ((v ushr 24) and 0xFF).toByte()
}
