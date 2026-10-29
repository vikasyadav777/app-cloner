package com.appcloner.dual

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appcloner.dual.ui.ClonerScreen
import com.appcloner.dual.ui.ClonerViewModel
import com.appcloner.dual.ui.theme.AppClonerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ClonerViewModel by viewModels()

    private val unknownSources = registerForActivityResult(StartActivityForResult()) {
        viewModel.onUnknownSourcesReturned(packageManager.canRequestPackageInstalls())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppClonerTheme {
                val state by viewModel.ui.collectAsStateWithLifecycle()
                ClonerScreen(
                    ui = state,
                    onTab = viewModel::setTab,
                    onQuery = viewModel::setQuery,
                    onToggleSystem = viewModel::toggleSystem,
                    onClone = viewModel::clone,
                    onOpen = viewModel::open,
                    onAskRemove = viewModel::askRemove,
                    onConfirmRemove = viewModel::confirmRemove,
                    onDismissRemove = viewModel::dismissRemove,
                    onAllowInstalls = { unknownSources.launch(viewModel.unknownSourcesIntent()) },
                    onDismissInstalls = viewModel::dismissInstalls,
                    onShowHow = viewModel::showHow,
                    onDismissBanner = viewModel::dismissBanner,
                    onStopWaiting = viewModel::stopWaiting,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
