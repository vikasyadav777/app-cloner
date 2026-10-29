package com.appcloner.dual.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appcloner.dual.R
import com.appcloner.dual.data.ListedClone
import com.appcloner.dual.data.PhoneApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClonerScreen(
    ui: ClonerUi,
    onTab: (Tab) -> Unit,
    onQuery: (String) -> Unit,
    onToggleSystem: () -> Unit,
    onClone: (PhoneApp) -> Unit,
    onOpen: (ListedClone) -> Unit,
    onAskRemove: (ListedClone) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    onAllowInstalls: () -> Unit,
    onDismissInstalls: () -> Unit,
    onShowHow: (Boolean) -> Unit,
    onDismissBanner: () -> Unit,
    onStopWaiting: () -> Unit,
) {
    val phoneTab = ui.tab == Tab.Phone
    val visible = if (phoneTab) {
        ui.apps.filter { app ->
            (ui.showSystem || !app.system) && matches(app.label, app.packageName, ui.query)
        }
    } else {
        emptyList()
    }
    val visibleClones = ui.clones.filter { matches(it.label, it.clonePackage, ui.query) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { onShowHow(true) }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_how))
                    }
                },
            )
        },
        bottomBar = {
            val busy = ui.busy
            if (busy != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(busy, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onStopWaiting) { Text(stringResource(R.string.stop_waiting)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val banner = ui.banner
            if (banner != null) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(banner, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = onDismissBanner) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dismiss))
                        }
                    }
                }
            }
            TabRow(selectedTabIndex = if (phoneTab) 0 else 1) {
                Tab(selected = phoneTab, onClick = { onTab(Tab.Phone) }, text = { Text(stringResource(R.string.tab_phone)) })
                Tab(selected = !phoneTab, onClick = { onTab(Tab.Clones) }, text = { Text(stringResource(R.string.tab_clones)) })
            }
            OutlinedTextField(
                value = ui.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.search)) },
            )
            if (phoneTab) {
                FilterChip(
                    selected = ui.showSystem,
                    onClick = onToggleSystem,
                    label = { Text(stringResource(R.string.show_system)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            when {
                ui.loading -> {
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                phoneTab && visible.isEmpty() -> Empty(stringResource(R.string.empty_apps))
                !phoneTab && visibleClones.isEmpty() -> Empty(stringResource(R.string.empty_clones))
                phoneTab -> {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            PhoneRow(app, ui.counts[app.packageName] ?: 0, ui.busy == null, onClone)
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(visibleClones, key = { it.clonePackage }) { clone ->
                            val source = ui.apps.firstOrNull { it.packageName == clone.sourcePackage }?.label
                                ?: clone.sourcePackage
                            CloneRow(clone, source, ui.busy == null, onOpen, onAskRemove)
                        }
                    }
                }
            }
        }
    }
    if (ui.showHow) {
        AlertDialog(
            onDismissRequest = { onShowHow(false) },
            title = { Text(stringResource(R.string.how_title)) },
            text = { Text(stringResource(R.string.how_body)) },
            confirmButton = { TextButton(onClick = { onShowHow(false) }) { Text(stringResource(R.string.dismiss)) } },
        )
    }
    if (ui.askInstalls) {
        AlertDialog(
            onDismissRequest = onDismissInstalls,
            title = { Text(stringResource(R.string.allow_title)) },
            text = { Text(stringResource(R.string.allow_body)) },
            confirmButton = { TextButton(onClick = onAllowInstalls) { Text(stringResource(R.string.allow_continue)) } },
            dismissButton = { TextButton(onClick = onDismissInstalls) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val removing = ui.pendingRemove
    if (removing != null) {
        AlertDialog(
            onDismissRequest = onDismissRemove,
            title = { Text(stringResource(R.string.remove_title)) },
            text = { Text(stringResource(R.string.remove_body, removing.label)) },
            confirmButton = { TextButton(onClick = onConfirmRemove) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = onDismissRemove) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PhoneRow(app: PhoneApp, count: Int, enabled: Boolean, onClone: (PhoneApp) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (count > 0) {
                Text(pluralStringResource(R.plurals.clone_count, count, count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onClone(app) }, enabled = enabled) { Text(stringResource(R.string.clone)) }
    }
}

@Composable
private fun CloneRow(
    clone: ListedClone,
    source: String,
    enabled: Boolean,
    onOpen: (ListedClone) -> Unit,
    onRemove: (ListedClone) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(clone.icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(clone.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.from_app, source), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onOpen(clone) }, enabled = enabled) { Text(stringResource(R.string.open)) }
        TextButton(onClick = { onRemove(clone) }, enabled = enabled) { Text(stringResource(R.string.remove)) }
    }
}

@Composable
private fun AppIcon(icon: android.graphics.Bitmap?) {
    if (icon != null) {
        Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
    } else {
        Spacer(Modifier.size(40.dp))
    }
}

@Composable
private fun ColumnScope.Empty(text: String) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun matches(label: String, packageName: String, query: String): Boolean {
    if (query.isBlank()) return true
    return label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)
}
