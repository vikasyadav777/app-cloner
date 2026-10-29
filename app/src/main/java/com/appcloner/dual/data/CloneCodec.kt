package com.appcloner.dual.data

import org.json.JSONArray
import org.json.JSONObject

object CloneCodec {
    fun encode(entries: List<CloneEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("clonePackage", entry.clonePackage)
                    .put("sourcePackage", entry.sourcePackage)
                    .put("label", entry.label),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<CloneEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    CloneEntry(
                        clonePackage = item.getString("clonePackage"),
                        sourcePackage = item.getString("sourcePackage"),
                        label = item.optString("label"),
                    ),
                )
            }
        }
    }
}
