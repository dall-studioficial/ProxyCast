/*
 * Copyright 2024 pyamsoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dall.app.proxycast.ui.info

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Device setup component for Wi-Fi Direct group configuration
 * Adapted from TetherFuseNet for ProxyCast
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenderDeviceSetup(
    modifier: Modifier = Modifier,
    isGroupOwner: Boolean,
    groupSsid: String,
    groupPassphrase: String,
    ipv4Address: String,
    ipv6Address: String,
    ssidError: String,
    passphraseError: String,
    savedSsid: String,
    savedPassphrase: String,
    onCreateGroup: (String, String, String, String) -> Unit,
    onStopGroup: () -> Unit
) {
    var ssidInput by remember { mutableStateOf(savedSsid) }
    var passwordInput by remember { mutableStateOf(savedPassphrase) }
    var showPassword by remember { mutableStateOf(false) }
    var selectedBand by remember { mutableStateOf("auto") }
    var selectedIpPreference by remember { mutableStateOf("auto") }
    var expandedBand by remember { mutableStateOf(false) }
    var expandedIpPref by remember { mutableStateOf(false) }
    
    val bandOptions = listOf("auto", "2.4", "5")
    val ipPrefOptions = listOf("auto", "IPv4", "IPv6")
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Device Setup",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // SSID Input
        OutlinedTextField(
            value = ssidInput,
            onValueChange = { ssidInput = it },
            label = { Text("Network Name (suffix only)") },
            placeholder = { Text("Optional - auto-prefixed with DIRECT-xy-") },
            singleLine = true,
            enabled = !isGroupOwner,
            isError = ssidError.isNotEmpty(),
            supportingText = if (ssidError.isNotEmpty()) {
                {
                    Text(
                        text = ssidError,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )

        // Password Input
        OutlinedTextField(
            value = passwordInput,
            onValueChange = { passwordInput = it },
            label = { Text("Password") },
            placeholder = { Text("Optional - Leave empty for default") },
            singleLine = true,
            enabled = !isGroupOwner,
            isError = passphraseError.isNotEmpty(),
            supportingText = if (passphraseError.isNotEmpty()) {
                {
                    Text(
                        text = passphraseError,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else null,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(
                        text = if (showPassword) "HIDE" else "SHOW",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )

        // Band Selection Dropdown
        ExposedDropdownMenuBox(
            expanded = expandedBand,
            onExpandedChange = { if (!isGroupOwner) expandedBand = !expandedBand }
        ) {
            OutlinedTextField(
                value = when(selectedBand) {
                    "2.4" -> "2.4 GHz"
                    "5" -> "5 GHz"
                    else -> "Auto"
                },
                onValueChange = {},
                readOnly = true,
                enabled = !isGroupOwner,
                label = { Text("Band Selection") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedBand) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(
                expanded = expandedBand,
                onDismissRequest = { expandedBand = false }
            ) {
                bandOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(when(option) {
                            "2.4" -> "2.4 GHz"
                            "5" -> "5 GHz"
                            else -> "Auto"
                        }) },
                        onClick = {
                            selectedBand = option
                            expandedBand = false
                        }
                    )
                }
            }
        }

        // IP Preference Dropdown
        ExposedDropdownMenuBox(
            expanded = expandedIpPref,
            onExpandedChange = { if (!isGroupOwner) expandedIpPref = !expandedIpPref }
        ) {
            OutlinedTextField(
                value = selectedIpPreference,
                onValueChange = {},
                readOnly = true,
                enabled = !isGroupOwner,
                label = { Text("IP Preference") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedIpPref) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(
                expanded = expandedIpPref,
                onDismissRequest = { expandedIpPref = false }
            ) {
                ipPrefOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            selectedIpPreference = option
                            expandedIpPref = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Information card about custom credentials
        if (!isGroupOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Wi-Fi Direct Configuration (Android 10+)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• SSID: Enter suffix only - automatically prefixed with 'DIRECT-XY-'\n" +
                               "  - For 3+ char input: XY = first 2 chars, rest is suffix\n" +
                               "  - Example: 'MyNetwork' → 'DIRECT-My-Network'\n" +
                               "• Max 32 chars total (trimmed if needed)\n" +
                               "• Passphrase: Optional, 8-63 characters if provided\n" +
                               "• Band: Choose 2.4 GHz, 5 GHz, or auto (requires API 29+)\n" +
                               "  - Not all devices support band selection\n" +
                               "  - Falls back to auto if unsupported\n" +
                               "• IP Preference: Display-only, shows negotiated addresses\n" +
                               "  - No enforcement, depends on device/stack\n" +
                               "• Leave empty to use system defaults\n" +
                               "• System may override; check displayed info after creation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        } else if (!isGroupOwner && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "System-Generated Credentials",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Android 8-9: Custom SSID, passphrase, and band selection are not supported. The system will generate credentials automatically. IP preference display is still available. Check the group info after creation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Show different buttons based on group owner status
        if (isGroupOwner) {
            Button(
                onClick = onStopGroup,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Stop Group + Proxy")
            }
        } else {
            Button(
                onClick = { onCreateGroup(ssidInput, passwordInput, selectedBand, selectedIpPreference) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("Create Group + Start Proxy (Host)")
            }
        }

        // Display current group info if available
        if (groupSsid.isNotEmpty() && groupSsid != "N/A") {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Current Group:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "SSID: $groupSsid",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Passphrase: $groupPassphrase",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (ipv4Address.isNotEmpty()) {
                        Text(
                            text = "IPv4: $ipv4Address",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (ipv6Address.isNotEmpty()) {
                        Text(
                            text = "IPv6: $ipv6Address",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
