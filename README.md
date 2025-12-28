# ProxyCast – Wi-Fi Direct Proxy POC

A proof-of-concept Android application demonstrating Wi-Fi Direct group creation with HTTP CONNECT proxy functionality, enabling one device to act as a network proxy for other connected devices.

## Overview

ProxyCast allows Android devices to create ad-hoc network connections using Wi-Fi Direct (Wi-Fi P2P) and route traffic through an HTTP CONNECT proxy server. One device acts as the **host** (group owner), creating a Wi-Fi Direct group and running a proxy server on port 8080. Other devices act as **clients**, discovering and connecting to the host to route their network traffic through the proxy.

## Features

- **Wi-Fi Direct Group Creation**: Host device creates a Wi-Fi Direct group with customizable SSID and passphrase (Android 10+)
- **HTTP CONNECT Proxy Server**: Runs on port 8080 to proxy client traffic
- **Peer Discovery & Connection**: Client devices can discover and connect to available Wi-Fi Direct groups
- **Group Credentials Display**: Shows actual SSID, passphrase, and IP address for easy client configuration
- **Foreground Service**: Proxy runs as a foreground service with persistent notification
- **Runtime Permission Handling**: Manages all required Android permissions dynamically
- **Modern Android Support**: Compatible with Android 8.0 (API 26) through Android 14+ (API 34+)

## Requirements

### Hardware & System
- **Physical Android Devices**: Wi-Fi Direct does not work in Android emulators
- **Minimum Android Version**: Android 8.0 (API 26)
- **Wi-Fi Hardware**: Both devices must have Wi-Fi enabled and support Wi-Fi Direct

### Permissions

The app requires the following permissions (requested at runtime):

| Permission | Purpose | Required On |
|-----------|---------|-------------|
| `ACCESS_FINE_LOCATION` | Wi-Fi Direct peer discovery | All versions |
| `ACCESS_COARSE_LOCATION` | Wi-Fi Direct on Android 12+ | API 31+ |
| `ACCESS_WIFI_STATE` | Read Wi-Fi state | All versions |
| `CHANGE_WIFI_STATE` | Modify Wi-Fi state | All versions |
| `ACCESS_NETWORK_STATE` | Monitor network connectivity | All versions |
| `INTERNET` | Network access for proxy | All versions |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct on Android 13+ | API 33+ |
| `FOREGROUND_SERVICE` | Run proxy as foreground service | API 26+ |
| `FOREGROUND_SERVICE_DATA_SYNC` | Foreground service type | API 34+ |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Foreground service type | API 34+ |
| `POST_NOTIFICATIONS` | Display service notification | API 33+ |

## Usage

### Host Device (Proxy Server)

1. **Launch the app** on the device that will act as the proxy server
2. **Grant all requested permissions** when prompted (location, Wi-Fi, notifications)
3. **Optional: Configure custom network credentials**
   - Enter a custom **Network Name suffix** in the text field (e.g., "MyNetwork")
     - The app automatically prefixes your input with `DIRECT-XY-`
     - For inputs of 3+ characters: XY = first 2 characters, remaining becomes suffix
       - Example: "MyNetwork" becomes "DIRECT-My-Network"
     - For 1-2 character inputs: padded with 'x' to form 2-character identifier
       - Example: "A" becomes "DIRECT-Ax-", "AB" becomes "DIRECT-AB-"
   - Enter a custom **Password** in the password field
   - **Validation Requirements**:
     - **SSID**: Suffix is auto-prefixed with `DIRECT-xy-`, maximum 32 characters total (suffix trimmed if necessary)
     - **Passphrase**: Must be 8-63 characters (strict WPA2 requirement)
     - The app will display validation errors and prevent group creation if inputs are invalid
   - **Note**: Custom SSID and passphrase require Android 10+ (API 29)
   - Leave fields empty to use system-generated defaults
   - **Fallback Behavior**: If the system rejects custom credentials (on Android 10+), the app automatically falls back to system-generated credentials and displays a notice
4. **Tap "Create Group + Start Proxy (Host)"**
   - The device creates a Wi-Fi Direct group
   - The proxy server starts on port 8080
   - A foreground notification appears confirming the proxy is running
5. **Note the displayed credentials**:
   - **SSID**: Network name for clients to connect to
   - **Passphrase**: Password for clients to join the network
   - **IP Address**: Host IP for proxy configuration (shown after connection)
   - **Important**: The displayed credentials show the actual values used by the system, which may differ from your input if the platform overrides them or if fallback occurs
6. **Share these credentials** with client devices

### Client Device

1. **Launch the app** on a device that will connect as a client
2. **Grant all requested permissions** when prompted
3. **Tap "Discover Peers (Client)"** to scan for available Wi-Fi Direct groups
   - The app will display the number of discovered peers
4. **Tap "Connect to First Peer"** to connect to the host device
   - The device will connect to the Wi-Fi Direct group
   - Connection status will be displayed in the UI
5. **Once connected**, the status will show:
   - Group SSID and passphrase
   - Group owner (host) IP address
   - Proxy configuration: `<host-ip>:8080`
6. **Configure apps to use the proxy**:
   - Set proxy address to the displayed host IP
   - Set proxy port to `8080`
   - Example: `192.168.49.1:8080`

## Compatibility Notes

### Android 10+ (API 29): Custom SSID and Passphrase

Starting with Android 10, you can set a custom network name (SSID) and passphrase when creating a Wi-Fi Direct group:
- Enter a suffix in the SSID field; the app automatically prefixes it with `DIRECT-XY-`
  - For 3+ character inputs: XY = first 2 characters, remaining becomes suffix
    - Example: "MyNetwork" becomes "DIRECT-My-Network"
  - For 1-2 character inputs: padded with 'x' to form 2-character identifier
    - Example: "A" becomes "DIRECT-Ax-", "AB" becomes "DIRECT-AB-"
- Use `WifiP2pConfig.Builder().setNetworkName(ssid).build()` internally for custom SSID
- Use `WifiP2pConfig.Builder().setPassphrase(password).build()` internally for custom passphrase
- Both methods can be chained before calling `.build()` to create the config
- **Validation Requirements**:
  - **SSID**: Automatically normalized to `DIRECT-xy-<suffix>` format, maximum 32 characters total
  - **Passphrase**: Strict requirement of 8-63 characters (WPA2 standard)
  - The app validates inputs and prevents group creation with invalid entries
- **Exception Handling**: The app wraps `setNetworkName`/`setPassphrase` calls in try/catch blocks
  - If `IllegalArgumentException` is thrown (e.g., invalid format), the app automatically falls back to `createGroup()` without custom config
  - A user-friendly notice is displayed when fallback occurs
- **Platform Behavior**: Some device manufacturers may override these values based on system settings
- The app displays the **actual credentials** after group creation using `requestGroupInfo()`, so you can verify the effective SSID and passphrase being used

### Android 8-9 (API 26-28): System-Generated Credentials

On Android 8.0 to 9.0, custom SSID and passphrase are not supported:
- The system automatically generates random credentials
- The app still validates inputs but will fallback to `createGroup()` without custom config on older APIs
- Credentials are displayed after group creation using `requestGroupInfo()`
- Host must share these credentials with clients manually

### Android 13+ (API 33): Additional Permissions

Android 13 and later require additional permissions:
- `NEARBY_WIFI_DEVICES`: Replaces location permission for Wi-Fi scanning
- `POST_NOTIFICATIONS`: Required to display foreground service notification

## Key Files

| File | Description |
|------|-------------|
| `MainActivity.kt` | Main UI and Wi-Fi Direct management logic |
| `WifiDirectReceiver.kt` | Broadcast receiver for Wi-Fi P2P events |
| `ProxyServerService.kt` | Foreground service implementing HTTP CONNECT proxy |
| `AndroidManifest.xml` | Declares permissions and service configuration |

## Limitations

This is a **proof-of-concept** implementation with the following limitations:

### Security
- ⚠️ **No authentication**: Any client can use the proxy without credentials
- ⚠️ **No encryption**: Traffic between client and proxy is not encrypted (beyond Wi-Fi Direct's WPA2)
- ⚠️ **No access control**: No filtering or restrictions on proxy usage
- **Not suitable for production use** or untrusted networks

### Functionality
- **Single group support**: Only one Wi-Fi Direct group at a time
- **No automatic reconnection**: Manual reconnection required if connection drops
- **Basic error handling**: Minimal error recovery and user feedback
- **Manual peer selection**: Connects to first discovered peer only
- **No persistent configuration**: Settings are lost when app is closed

### Testing
- **Physical devices only**: Wi-Fi Direct does not work in Android emulators
- **Wi-Fi Direct support**: Both devices must support Wi-Fi Direct
- **Proximity required**: Devices must be within Wi-Fi Direct range (typically ~50-100m)

## Next Steps

To evolve this POC into a more robust solution, consider:

### Security Enhancements
- [ ] Add authentication mechanism for proxy access
- [ ] Implement TLS/SSL for proxy connections
- [ ] Add access control lists (ACLs) for client filtering
- [ ] Implement secure credential exchange

### Feature Additions
- [ ] Multi-client support with connection management UI
- [ ] Automatic reconnection on connection loss
- [ ] Persistent configuration storage
- [ ] QR code sharing for easy credential distribution
- [ ] Connection statistics and monitoring
- [ ] Support for multiple proxy protocols (SOCKS5, etc.)

### User Experience
- [ ] Improved error messages and recovery suggestions
- [ ] Connection quality indicators
- [ ] Saved peer list for quick reconnection
- [ ] Advanced settings for power users
- [ ] Network traffic statistics

### Code Quality
- [ ] Comprehensive error handling and logging
- [ ] Unit and integration tests
- [ ] Performance optimization for high-throughput scenarios
- [ ] Code documentation and architecture diagrams

## Testing

1. **Install** the app on two physical Android devices (minimum Android 8.0)
2. **Follow the host setup** on one device to create a group and start the proxy
3. **Follow the client setup** on the second device to discover and connect
4. **Test the proxy** by configuring a browser or app to use `<host-ip>:8080` as the proxy
5. **Verify traffic routing** by accessing websites or services through the proxy

## Technical Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Async Processing**: Kotlin Coroutines
- **Networking**: Wi-Fi P2P (Wi-Fi Direct) API, Socket Programming
- **Architecture**: Service-based with broadcast receivers

## Security Warning

⚠️ **This is a proof-of-concept for educational and testing purposes only.**

- Do not use in production environments
- Do not use on untrusted networks
- Do not route sensitive traffic without additional encryption
- Be aware that proxy traffic can be intercepted
- Understand the security implications before deployment

## License

See repository license for details.

## Contributing

This is a proof-of-concept project. For production use, significant security and functionality enhancements are required.
