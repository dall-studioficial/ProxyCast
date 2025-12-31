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

package dall.app.proxycast.server.broadcast.wifidirect

import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.annotation.CheckResult
import dall.app.proxycast.server.ServerDefaults
import dall.app.proxycast.server.ServerNetworkBand
import dall.app.proxycast.server.WifiPreferences
import kotlinx.coroutines.flow.first

/**
 * Wi-Fi Direct configuration interface
 */
internal interface WiDiConfig {
    @CheckResult
    suspend fun getConfiguration(): WifiP2pConfig?
}

/**
 * Wi-Fi Direct configuration implementation
 */
internal class WiDiConfigImpl(
    private val preferences: WifiPreferences,
) : WiDiConfig {

    @CheckResult
    private fun resolveBand(band: ServerNetworkBand): Int {
        return when (band) {
            ServerNetworkBand.LEGACY -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
            ServerNetworkBand.MODERN -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
            ServerNetworkBand.MODERN_6 -> {
                // 6GHz support requires API 35+
                if (Build.VERSION.SDK_INT >= 35) {
                    6 // WifiP2pConfig.GROUP_OWNER_BAND_6GHZ when available
                } else {
                    WifiP2pConfig.GROUP_OWNER_BAND_AUTO
                }
            }
        }
    }

    override suspend fun getConfiguration(): WifiP2pConfig? {
        if (!ServerDefaults.canUseCustomConfig()) {
            return null
        }

        val ssid = preferences.listenForSsid().first()
        val password = preferences.listenForPassword().first()
        val band = preferences.listenForBand().first()

        if (ssid.isBlank() || password.isBlank()) {
            return null
        }

        val fullSsid = ServerDefaults.asWifiSsid(ssid)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiP2pConfig.Builder()
                .setNetworkName(fullSsid)
                .setPassphrase(password)
                .setGroupOperatingBand(resolveBand(band))
                .build()
        } else {
            null
        }
    }
}
