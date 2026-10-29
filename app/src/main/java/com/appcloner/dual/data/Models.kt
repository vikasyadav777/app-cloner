package com.appcloner.dual.data

import android.graphics.Bitmap

data class PhoneApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val versionCode: Long,
    val icon: Bitmap?,
)

data class CloneEntry(
    val clonePackage: String,
    val sourcePackage: String,
    val label: String,
)

data class ListedClone(
    val clonePackage: String,
    val sourcePackage: String,
    val label: String,
    val icon: Bitmap?,
)
