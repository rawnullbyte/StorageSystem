package com.storagesystem.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.storagesystem.data.models.ScanMode

/**
 * Top bar containing mode selection tabs and the layer dropdown.
 */
data class ModeOption(
    val mode: ScanMode,
    val label: String,
    val icon: ImageVector
)

private val modeOptions = listOf(
    ModeOption(ScanMode.AUTO_IMPORT_CONTAINERS, "Import", Icons.Default.CameraAlt),
    ModeOption(ScanMode.ASSIGN_BAG, "Assign", Icons.Default.Sell),
    ModeOption(ScanMode.SEARCH, "Search", Icons.Default.Search)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelector(
    currentMode: ScanMode,
    onModeChange: (ScanMode) -> Unit,
    layers: List<com.storagesystem.data.models.StorageLayer>,
    selectedLayerId: Int?,
    onLayerSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        // Mode tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            modeOptions.forEach { option ->
                FilterChip(
                    selected = currentMode == option.mode,
                    onClick = { onModeChange(option.mode) },
                    label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = {
                        Icon(
                            option.icon,
                            contentDescription = option.label,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Layer selector (shown for Import and Assign modes)
        if (currentMode != ScanMode.SEARCH) {
            if (layers.isEmpty()) {
                Text(
                    "No layers available — add one via the Dashboard",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                LayerDropdown(
                    layers = layers,
                    selectedLayerId = selectedLayerId,
                    onLayerSelected = onLayerSelected
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerDropdown(
    layers: List<com.storagesystem.data.models.StorageLayer>,
    selectedLayerId: Int?,
    onLayerSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = layers.find { it.id == selectedLayerId }?.name ?: "Select layer"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Layer") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .height(48.dp),
            textStyle = MaterialTheme.typography.bodySmall
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            layers.forEach { layer ->
                DropdownMenuItem(
                    text = { Text(layer.name) },
                    onClick = {
                        onLayerSelected(layer.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
