package com.appcloner.dual.clone

import android.content.Intent

internal object InstallEvents {
    var listener: ((Intent) -> Unit)? = null
}
