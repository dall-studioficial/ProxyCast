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

package dall.app.proxycast.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stub for Wi-Fi preferences
 * TODO: Implement actual preference storage if needed
 */
interface WifiPreferences {
    fun listenForSsid(): Flow<String>
    fun listenForPassword(): Flow<String>
    fun listenForBand(): Flow<ServerNetworkBand>
}

class WifiPreferencesStub : WifiPreferences {
    override fun listenForSsid(): Flow<String> = MutableStateFlow(ServerDefaults.WIFI_SSID)
    // TODO: Generate secure default password or integrate with actual preferences
    // Empty password will cause WifiDirectServer to use system-generated credentials
    override fun listenForPassword(): Flow<String> = MutableStateFlow("")
    override fun listenForBand(): Flow<ServerNetworkBand> = MutableStateFlow(ServerDefaults.WIFI_NETWORK_BAND)
}
