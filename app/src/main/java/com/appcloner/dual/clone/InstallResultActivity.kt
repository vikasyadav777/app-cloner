package com.appcloner.dual.clone

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import com.appcloner.dual.data.CloneEntry
import com.appcloner.dual.data.CloneIndex

class InstallResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.confirmationIntent()
            if (confirm != null) startActivity(confirm)
            finish()
            return
        }
        val op = intent.getStringExtra(InstallContract.EXTRA_OP)
        val clonePackage = intent.getStringExtra(InstallContract.EXTRA_CLONE_PACKAGE).orEmpty()
        val label = intent.getStringExtra(InstallContract.EXTRA_LABEL).orEmpty()
        val source = intent.getStringExtra(InstallContract.EXTRA_SOURCE).orEmpty()
        if (status == PackageInstaller.STATUS_SUCCESS && clonePackage.isNotEmpty()) {
            val current = CloneIndex.load(this).filter { it.clonePackage != clonePackage }
            val next = if (op == InstallContract.OP_INSTALL) {
                current + CloneEntry(clonePackage, source, label)
            } else {
                current
            }
            CloneIndex.save(this, next)
        }
        InstallEvents.listener?.invoke(intent)
        finish()
    }

    private fun Intent.confirmationIntent(): Intent? {
        val key = Intent.EXTRA_INTENT
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }
}
