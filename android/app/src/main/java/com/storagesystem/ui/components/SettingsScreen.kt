package com.storagesystem.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storagesystem.data.ServerSettings

/**
 * Settings screen where the user can configure the backend server URL.
 *
 * @param onDismiss Called when the user closes settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDismiss: () -> Unit) {
    var serverUrl by remember { mutableStateOf(ServerSettings.apiBaseUrl) }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Settings") },
                navigationIcon = {
                    TextButton(onClick = onDismiss) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Server URL",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("http://192.168.1.100:8000") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "The HTTP base URL of the backend server. " +
                        "The WebSocket URL is derived automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    ServerSettings.setFromHttpBase(serverUrl)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = serverUrl.isNotBlank()
            ) {
                Text(if (saved) "Saved!" else "Save & Apply")
            }

            if (saved) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        "Settings saved. Reconnect the app or wait for auto-reconnect.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
