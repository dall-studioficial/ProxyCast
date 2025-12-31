/*
 * Copyright 2025 pyamsoft
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

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dall.app.proxycast.R

/**
 * App setup component for VPN client and proxy settings
 * Adapted from TetherFuseNet for ProxyCast
 */
@Composable
fun RenderAppSetup(
    modifier: Modifier = Modifier,
    isGroupOwner: Boolean,
    isVpnActive: Boolean,
    detectedGatewayIp: String,
    onStartVpnClient: () -> Unit,
    onStopVpnClient: () -> Unit
) {
    // VPN Client section - only show when not group owner
    if (!isGroupOwner) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "App Setup",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            Text(
                text = "Client Mode (Auto-Detection)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            Text(
                text = "Connect to Wi-Fi Direct network created by host. Gateway IP is auto-detected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // Display detected gateway IP
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (detectedGatewayIp.isNotEmpty()) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (detectedGatewayIp.isNotEmpty()) 
                            stringResource(R.string.host_detected, detectedGatewayIp)
                        else 
                            stringResource(R.string.host_not_detected),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (detectedGatewayIp.isNotEmpty())
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (detectedGatewayIp.isNotEmpty()) 
                            stringResource(R.string.gateway_ready_msg)
                        else 
                            stringResource(R.string.gateway_not_detected_msg),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (detectedGatewayIp.isNotEmpty())
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            if (isVpnActive) {
                Button(
                    onClick = onStopVpnClient,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.btn_disconnect_vpn))
                }
            } else {
                Button(
                    onClick = onStartVpnClient,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(stringResource(R.string.btn_start_vpn))
                }
            }
        }
    }
}
