package com.storagesystem.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Search bar for ScanMode.SEARCH.
 *
 * @param initialValue  Initial search term.
 * @param onSearch  Called when the user submits a search.
 * @param onClear  Called to clear the search and reset highlighting.
 * @param modifier  Compose modifier.
 */
@Composable
fun SearchBar(
    initialValue: String = "",
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Container name/UUID or part number") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search")
            },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        text = ""
                        onClear()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).height(52.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = { onSearch(text) },
            enabled = text.isNotBlank()
        ) {
            Text("Find")
        }
    }
}
