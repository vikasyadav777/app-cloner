package com.appcloner.dual.data

import android.content.Context

object CloneIndex {
    private const val PREFS = "clone_index"
    private const val KEY = "entries"

    fun load(context: Context): List<CloneEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return try {
            CloneCodec.decode(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, entries: List<CloneEntry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, CloneCodec.encode(entries))
            .commit()
    }
}
