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

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Main info screen combining device setup and app setup
 * Adapted from TetherFuseNet for ProxyCast
 */
@Composable
fun InfoScreen(
    modifier: Modifier = Modifier,
    statusText: String,
    groupSsid: String,
    groupPassphrase: String,
    isGroupOwner: Boolean,
    ssidError: String,
    passphraseError: String,
    ipv4Address: String,
    ipv6Address: String,
    isVpnActive: Boolean,
    savedSsid: String,
    savedPassphrase: String,
    detectedGatewayIp: String,
    onCreateGroup: (String, String, String, String) -> Unit,
    onStopGroup: () -> Unit,
    onStartVpnClient: () -> Unit,
    onStopVpnClient: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Wi-Fi Direct Proxy",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Device Setup Section
        RenderDeviceSetup(
            isGroupOwner = isGroupOwner,
            groupSsid = groupSsid,
            groupPassphrase = groupPassphrase,
            ipv4Address = ipv4Address,
            ipv6Address = ipv6Address,
            ssidError = ssidError,
            passphraseError = passphraseError,
            savedSsid = savedSsid,
            savedPassphrase = savedPassphrase,
            onCreateGroup = onCreateGroup,
            onStopGroup = onStopGroup
        )

        // App Setup Section (VPN Client)
        RenderAppSetup(
            isGroupOwner = isGroupOwner,
            isVpnActive = isVpnActive,
            detectedGatewayIp = detectedGatewayIp,
            onStartVpnClient = onStartVpnClient,
            onStopVpnClient = onStopVpnClient
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Status Display
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
