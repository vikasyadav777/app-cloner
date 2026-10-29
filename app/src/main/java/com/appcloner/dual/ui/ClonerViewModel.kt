package com.appcloner.dual.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appcloner.dual.R
import com.appcloner.dual.clone.CloneEngine
import com.appcloner.dual.clone.CloneInstaller
import com.appcloner.dual.clone.CloneNames
import com.appcloner.dual.clone.InstallContract
import com.appcloner.dual.clone.InstallEvents
import com.appcloner.dual.data.AppRepository
import com.appcloner.dual.data.CloneIndex
import com.appcloner.dual.data.ListedClone
import com.appcloner.dual.data.PhoneApp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { Phone, Clones }

data class ClonerUi(
    val loading: Boolean = true,
    val tab: Tab = Tab.Phone,
    val query: String = "",
    val showSystem: Boolean = false,
    val apps: List<PhoneApp> = emptyList(),
    val clones: List<ListedClone> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val busy: String? = null,
    val banner: String? = null,
    val askInstalls: Boolean = false,
    val pendingRemove: ListedClone? = null,
    val showHow: Boolean = false,
)

class ClonerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val _ui = MutableStateFlow(ClonerUi())
    val ui: StateFlow<ClonerUi> = _ui.asStateFlow()
    private var pending: PhoneApp? = null

    init {
        InstallEvents.listener = { onInstallResult(it) }
        refresh()
    }

    override fun onCleared() {
        if (InstallEvents.listener != null) InstallEvents.listener = null
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    fun setTab(tab: Tab) = _ui.update { it.copy(tab = tab) }

    fun setQuery(query: String) = _ui.update { it.copy(query = query) }

    fun toggleSystem() = _ui.update { it.copy(showSystem = !it.showSystem) }

    fun dismissBanner() = _ui.update { it.copy(banner = null) }

    fun showHow(show: Boolean) = _ui.update { it.copy(showHow = show) }

    fun stopWaiting() = _ui.update { it.copy(busy = null) }

    fun clone(phoneApp: PhoneApp) {
        if (_ui.value.busy != null) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            pending = phoneApp
            _ui.update { it.copy(askInstalls = true, banner = null) }
            return
        }
        startClone(phoneApp)
    }

    fun dismissInstalls() {
        pending = null
        _ui.update { it.copy(askInstalls = false) }
    }

    fun unknownSourcesIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${app.packageName}"),
        )
    }

    fun onUnknownSourcesReturned(allowed: Boolean) {
        val phoneApp = pending
        pending = null
        _ui.update { it.copy(askInstalls = false) }
        if (allowed && phoneApp != null) {
            startClone(phoneApp)
        } else if (!allowed) {
            _ui.update { it.copy(banner = app.getString(R.string.allow_body)) }
        }
    }

    fun askRemove(clone: ListedClone) = _ui.update { it.copy(pendingRemove = clone) }

    fun dismissRemove() = _ui.update { it.copy(pendingRemove = null) }

    fun confirmRemove() {
        val clone = _ui.value.pendingRemove ?: return
        _ui.update { it.copy(pendingRemove = null, busy = app.getString(R.string.removing, clone.label), banner = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CloneInstaller.uninstall(app, clone.clonePackage, clone.label, clone.sourcePackage)
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, banner = e.message ?: app.getString(R.string.install_failed)) }
            }
        }
    }

    fun open(clone: ListedClone) {
        val intent = app.packageManager.getLaunchIntentForPackage(clone.clonePackage)
        if (intent == null) {
            _ui.update { it.copy(banner = app.getString(R.string.no_screen)) }
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    private fun startClone(phoneApp: PhoneApp) {
        viewModelScope.launch {
            val directory = File(app.cacheDir, "clone-work/${phoneApp.packageName.hashCode().toUInt()}")
            try {
                val (newPackage, number) = withContext(Dispatchers.IO) {
                    val reserved = CloneIndex.load(app)
                        .filter { it.sourcePackage == phoneApp.packageName }
                        .map { it.clonePackage }
                        .toSet()
                    CloneNames.next(app.packageManager, phoneApp.packageName, reserved)
                }
                val label = "${phoneApp.label.ifBlank { phoneApp.packageName }} $number"
                _ui.update { it.copy(busy = app.getString(R.string.preparing, label), banner = null) }
                withContext(Dispatchers.IO) {
                    directory.deleteRecursively()
                    val files = CloneEngine.build(app, directory, phoneApp.packageName, newPackage, label) { message ->
                        _ui.update { it.copy(busy = message) }
                    }
                    CloneInstaller.install(app, files, newPackage, label, phoneApp.packageName)
                }
                _ui.update { it.copy(busy = app.getString(R.string.installing, label)) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, banner = e.message ?: app.getString(R.string.install_failed)) }
            } finally {
                withContext(Dispatchers.IO) { directory.deleteRecursively() }
            }
        }
    }

    private fun onInstallResult(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val op = intent.getStringExtra(InstallContract.EXTRA_OP)
        val label = intent.getStringExtra(InstallContract.EXTRA_LABEL).orEmpty()
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val banner = when {
            status == PackageInstaller.STATUS_SUCCESS && op == InstallContract.OP_INSTALL ->
                app.getString(R.string.installed_banner, label)
            status == PackageInstaller.STATUS_SUCCESS && op == InstallContract.OP_UNINSTALL ->
                app.getString(R.string.removed_banner, label)
            !message.isNullOrBlank() -> message
            else -> app.getString(R.string.install_failed)
        }
        _ui.update { it.copy(busy = null, banner = banner) }
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    private fun load() {
        val installed = AppRepository.load(app)
        val byPackage = installed.associateBy { it.packageName }
        val remembered = CloneIndex.load(app).filter { it.clonePackage in byPackage }
        val stored = CloneIndex.load(app)
        if (remembered.map { it.clonePackage }.toSet() != stored.map { it.clonePackage }.toSet()) {
            CloneIndex.save(app, remembered)
        }
        val clonePackages = remembered.map { it.clonePackage }.toSet()
        val sources = installed.filter { it.packageName !in clonePackages }
        val clones = remembered.map { entry ->
            val phone = byPackage[entry.clonePackage]
            ListedClone(
                clonePackage = entry.clonePackage,
                sourcePackage = entry.sourcePackage,
                label = phone?.label?.ifBlank { entry.label } ?: entry.label,
                icon = phone?.icon,
            )
        }.sortedBy { it.label.lowercase() }
        val counts = clones.groupingBy { it.sourcePackage }.eachCount()
        _ui.update {
            it.copy(loading = false, apps = sources, clones = clones, counts = counts)
        }
    }
}
