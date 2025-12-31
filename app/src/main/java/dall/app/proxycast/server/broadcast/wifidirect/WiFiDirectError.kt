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

import android.net.wifi.p2p.WifiP2pManager
import androidx.annotation.CheckResult

/**
 * Wi-Fi Direct error handling
 */
internal object WiFiDirectError {

    enum class Reason(val displayReason: String) {
        ERROR("An internal error occurred"),
        P2P_UNSUPPORTED("Wi-Fi Direct is not supported on this device"),
        BUSY("The system is busy and cannot process the request"),
        NO_SERVICE_REQUESTS("No service requests are registered"),
        UNKNOWN("An unknown error occurred");

        companion object {
            @JvmStatic
            @CheckResult
            fun parseReason(code: Int): Reason {
                return when (code) {
                    WifiP2pManager.ERROR -> ERROR
                    WifiP2pManager.P2P_UNSUPPORTED -> P2P_UNSUPPORTED
                    WifiP2pManager.BUSY -> BUSY
                    WifiP2pManager.NO_SERVICE_REQUESTS -> NO_SERVICE_REQUESTS
                    else -> UNKNOWN
                }
            }
        }
    }
}
