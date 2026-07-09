package com.storagesystem.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storagesystem.data.ServerSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDismiss: () -> Unit) {
    var serverUrl by remember { mutableStateOf(ServerSettings.apiBaseUrl) }
    var autoScan by remember { mutableStateOf(ServerSettings.autoScan()) }
    var keepScreenOn by remember { mutableStateOf(ServerSettings.keepScreenOn()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Server URL
            Text("Server URL", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("http://192.168.1.100:8000") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { ServerSettings.setFromHttpBase(serverUrl) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Server URL") }

            Divider()

            // Auto-scan toggle
            Text("Scanning", style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-add containers & components", style = MaterialTheme.typography.bodyLarge)
                        Text("Auto-register on detection without tapping", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoScan, onCheckedChange = { autoScan = it; ServerSettings.setAutoScan(it) })
                }
            }

            // Always-on toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                        Text("Screen never dims while app is open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = keepScreenOn, onCheckedChange = { keepScreenOn = it; ServerSettings.setKeepScreenOn(it) })
                }
            }
        }
    }
}
