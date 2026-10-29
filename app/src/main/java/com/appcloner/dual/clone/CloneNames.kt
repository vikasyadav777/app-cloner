package com.appcloner.dual.clone

internal object CloneNames {
    fun next(sourcePackage: String, installed: Set<String>): Pair<String, Int> {
        for (n in 2..99) {
            val full = "c$n.$sourcePackage"
            val pkg = if (full.length < 128) full else "c$n.x${hash(sourcePackage)}"
            if (pkg !in installed && pkg.length < 128) return pkg to n
        }
        throw IllegalStateException("This app already has as many clones as Clone Space can install.")
    }

    private fun hash(sourcePackage: String): String {
        return sourcePackage.hashCode().toUInt().toString(16).padStart(8, '0')
    }
}
