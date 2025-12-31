# Wi-Fi Direct + HTTP CONNECT Proxy POC

## Overview
This is a proof-of-concept implementation of a Wi-Fi Direct proxy server for Android. It allows one device to act as a proxy server (host) and other devices to connect as clients and route their traffic through the proxy.

The app features **automatic host IP detection** (similar to pdaNet), which automatically detects the host IP via the Wi-Fi Direct gateway, eliminating the need for manual peer discovery or configuration.

## Features
- Wi-Fi Direct group creation (host mode) with custom SSID and passphrase
- **Automatic host IP detection** via Wi-Fi Direct gateway for clients
- **Simplified single-button client workflow** - "Iniciar VPN / Conectar"
- HTTP CONNECT proxy server on port 8080
- VPN-based automatic traffic routing for clients
- Display of actual group SSID and passphrase
- Foreground service with notification
- Runtime permission handling
- Support for Android 8.0 (API 26) to latest versions

## Usage

### Host Device (Proxy Server)
1. Open the app
2. Grant all requested permissions (location, Wi-Fi, notifications)
3. (Optional) Enter a custom network name (SSID) and password
   - Leave empty to use system-generated defaults
   - Custom SSID/password requires Android 10+ (API 29)
4. Tap "Create Group + Start Proxy (Host)"
5. The device will create a Wi-Fi Direct group and start the proxy server on port 8080
6. Note the SSID, passphrase, and IP address shown in the UI
7. Share these credentials with client devices

### Client Device (Automatic Detection)
1. Open the app
2. Grant all requested permissions
3. Go to your device's Wi-Fi settings and connect to the Wi-Fi Direct network (SSID starts with `DIRECT-`)
4. Return to the app - it will automatically detect the host IP (gateway)
5. The UI will show "**Host Detectado: [gateway IP]**" (e.g., `192.168.49.1`)

**VPN Client Mode:**
6. Tap "Iniciar VPN / Conectar" to enable automatic traffic routing
7. Grant VPN permission when prompted (first time only)
8. All device traffic will now automatically route through the proxy
9. Tap "Desconectar VPN" when you want to disconnect

**How it works:**
- Uses `ConnectivityManager` to detect active Wi-Fi connection
- Extracts gateway IP from `LinkProperties` (typically `192.168.49.1` for Wi-Fi Direct)
- No manual peer discovery needed!

## Requirements
- Android 8.0 (API 26) or higher
- Physical Android devices (Wi-Fi Direct doesn't work in emulators)
- Wi-Fi must be enabled on both devices

## Implementation Details

### Components
- **MainActivity**: UI and Wi-Fi Direct management
- **WifiDirectReceiver**: Broadcast receiver for Wi-Fi P2P events
- **ProxyServerService**: Foreground service implementing HTTP CONNECT proxy (host mode)
- **VpnProxyService**: VPN service for automatic traffic routing through proxy (client mode)

### Permissions
- `ACCESS_FINE_LOCATION`: Required for Wi-Fi Direct peer discovery
- `ACCESS_COARSE_LOCATION`: Required for Android 12+ Wi-Fi Direct
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE`: Wi-Fi management
- `ACCESS_NETWORK_STATE`: Network state monitoring
- `INTERNET`: Network access
- `NEARBY_WIFI_DEVICES`: Android 13+ Wi-Fi Direct permission
- `FOREGROUND_SERVICE*`: For running proxy as foreground service
- `POST_NOTIFICATIONS`: Android 13+ notification permission
- `BIND_VPN_SERVICE`: For VPN client functionality

### Custom SSID and Passphrase (Android 10+)
On Android 10 (API 29) and higher, you can configure a custom network name (SSID) and password when creating a Wi-Fi Direct group:
- Enter values in the UI text fields before creating the group
- Leave empty to use system-generated credentials
- The app displays the actual SSID and passphrase after group creation
- Some devices may override custom values based on system settings

## Security Notes
⚠️ **This is a proof-of-concept only!**
- No authentication or encryption
- No access control
- Not suitable for production use
- Only use on trusted networks for testing

## Known Limitations
- Basic error handling
- Single group support
- No automatic reconnection
- POC-level implementation (minimal features)

## Testing
1. Install the app on two physical Android devices
2. Follow the host and client setup procedures above
3. Test the proxy by configuring a browser or app to use the proxy address

## Technical Stack
- Kotlin
- Jetpack Compose for UI
- Coroutines for async operations
- Wi-Fi P2P (Wi-Fi Direct) API
- Socket programming for proxy implementation
