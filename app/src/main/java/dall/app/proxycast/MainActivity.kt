package dall.app.proxycast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dall.app.proxycast.ui.theme.ProxyCastTheme
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_SSID_IDENTIFIER = "xx"
        private const val PREFS_NAME = "ProxyCastPrefs"
        private const val PREF_SSID = "saved_ssid"
        private const val PREF_PASSPHRASE = "saved_passphrase"
    }

    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: WifiDirectReceiver
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var statusText by mutableStateOf("Ready")
    private var isWifiP2pEnabled by mutableStateOf(false)
    private var peersList = mutableStateListOf<WifiP2pDevice>()
    private var groupSsid by mutableStateOf("")
    private var groupPassphrase by mutableStateOf("")
    private var isGroupOwner by mutableStateOf(false)
    private var ssidError by mutableStateOf("")
    private var passphraseError by mutableStateOf("")
    private var ipv4Address by mutableStateOf("")
    private var ipv6Address by mutableStateOf("")
    private var isVpnActive by mutableStateOf(false)
    private var proxyHostAddress by mutableStateOf("")
    private var savedSsid by mutableStateOf("")
    private var savedPassphrase by mutableStateOf("")
    private var detectedGatewayIp by mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            statusText = "Permissions granted. Ready to use Wi-Fi Direct."
        } else {
            statusText = "Required permissions not granted."
        }
    }
    
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d(TAG, "VPN permission granted, starting VPN service")
            Toast.makeText(this, "VPN permission granted. Starting VPN...", Toast.LENGTH_SHORT).show()
            startVpnProxyService()
        } else {
            Log.e(TAG, "VPN permission denied by user")
            statusText = "VPN permission denied. Cannot start VPN client."
            Toast.makeText(this, "VPN permission denied. Cannot start VPN.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load saved SSID and passphrase from SharedPreferences
        loadSavedCredentials()

        // Initialize Wi-Fi P2P with null safety
        val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            Log.e(TAG, "Wi-Fi P2P is not supported on this device")
            setContent {
                ProxyCastTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Wi-Fi Direct Not Supported",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "This device does not support Wi-Fi Direct (Wi-Fi P2P).",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            return
        }
        
        wifiP2pManager = manager
        channel = wifiP2pManager.initialize(this, mainLooper, null)
        receiver = WifiDirectReceiver(wifiP2pManager, channel, this)

        // Request permissions
        requestRequiredPermissions()

        setContent {
            ProxyCastTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WifiDirectProxyScreen(
                        modifier = Modifier.padding(innerPadding),
                        statusText = statusText,
                        groupSsid = groupSsid,
                        groupPassphrase = groupPassphrase,
                        isGroupOwner = isGroupOwner,
                        ssidError = ssidError,
                        passphraseError = passphraseError,
                        ipv4Address = ipv4Address,
                        ipv6Address = ipv6Address,
                        isVpnActive = isVpnActive,
                        savedSsid = savedSsid,
                        savedPassphrase = savedPassphrase,
                        detectedGatewayIp = detectedGatewayIp,
                        onCreateGroup = { ssid, password, band, ipPref -> createGroup(ssid, password, band, ipPref) },
                        onStopGroup = { stopGroup() },
                        onStartVpnClient = { startVpnClient() },
                        onStopVpnClient = { stopVpnClient() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, flags)
        } else {
            registerReceiver(receiver, intentFilter)
        }
        
        // Update detected gateway IP when activity resumes
        updateDetectedGatewayIp()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    /**
     * Normalizes SSID input to Wi-Fi Direct required format.
     * Automatically adds DIRECT-xy- prefix if not present, trims whitespace, and limits to 32 chars.
     * @param input User-provided SSID input
     * @return Normalized SSID with DIRECT-xy- prefix, or empty string if input is empty
     */
    private fun normalizeSsid(input: String): String {
        if (input.isEmpty()) {
            return "" // Empty means use system default
        }
        
        val trimmed = input.trim()
        
        // If already starts with DIRECT-, keep it (user may have specific format)
        if (trimmed.startsWith("DIRECT-", ignoreCase = true)) {
            // Preserve original case and limit to 32 chars
            if (trimmed.length > 7) {  // Has content after "DIRECT-" (which is 7 chars)
                return trimmed.take(32)
            } else {
                // Input is exactly "DIRECT-" or shorter - add default identifier
                return "DIRECT-$DEFAULT_SSID_IDENTIFIER-".take(32)
            }
        }
        
        // Auto-add DIRECT-xy- prefix
        // For short inputs (1-2 chars), use them as the XY identifier (padded if needed)
        // For longer inputs, use first 2 chars as XY identifier, remaining as suffix
        if (trimmed.length <= 2) {
            // Short input - becomes the XY identifier (padded with 'x' if single char)
            val uniqueId = if (trimmed.length == 2) {
                trimmed
            } else {
                trimmed + "x"
            }
            return "DIRECT-$uniqueId-"
        }
        
        // Long input - use first 2 chars as XY identifier, rest as suffix
        val uniqueId = trimmed.substring(0, 2)
        val suffix = trimmed.substring(2)
        val withPrefix = "DIRECT-$uniqueId-$suffix"
        return withPrefix.take(32)
    }
    
    /**
     * Validates SSID according to Wi-Fi Direct requirements.
     * SSID must start with "DIRECT-" and be at most 32 characters long.
     */
    private fun validateSsid(ssid: String): String? {
        if (ssid.isEmpty()) {
            return null // Empty is allowed (will use default)
        }
        
        if (ssid.length > 32) {
            return "SSID must be at most 32 characters"
        }
        
        // Check for valid characters (printable ASCII)
        if (!ssid.all { it.code in 32..126 }) {
            return "SSID must contain only printable ASCII characters"
        }
        
        // Strict requirement - must start with DIRECT-
        if (!ssid.startsWith("DIRECT-")) {
            return "SSID must start with 'DIRECT-' (auto-normalized)"
        }
        
        return null
    }

    /**
     * Validates passphrase according to WPA2 requirements.
     * Passphrase must be 8-63 printable ASCII characters.
     */
    private fun validatePassphrase(passphrase: String): String? {
        if (passphrase.isEmpty()) {
            return null // Empty is allowed (will use default)
        }
        
        if (passphrase.length < 8) {
            return "Passphrase must be at least 8 characters"
        }
        
        if (passphrase.length > 63) {
            return "Passphrase must be at most 63 characters"
        }
        
        // Check for valid characters (printable ASCII)
        if (!passphrase.all { it.code in 32..126 }) {
            return "Passphrase must contain only printable ASCII characters"
        }
        
        return null
    }

    @SuppressLint("MissingPermission")
    private fun createGroup(ssid: String = "", password: String = "", band: String = "auto", ipPreference: String = "auto") {
        if (!checkPermissions()) {
            statusText = "Missing required permissions"
            return
        }

        // Normalize SSID input (auto-prefix with DIRECT-xy- if needed)
        val normalizedSsid = normalizeSsid(ssid)
        
        // Validate normalized inputs
        val ssidValidationError = validateSsid(normalizedSsid)
        val passphraseValidationError = validatePassphrase(password)
        
        // Set error messages
        ssidError = ssidValidationError ?: ""
        passphraseError = passphraseValidationError ?: ""
        
        // If there are any validation errors, don't proceed
        if (ssidValidationError != null || passphraseValidationError != null) {
            statusText = "Please fix validation errors before creating group"
            return
        }

        // Save credentials for next app restart
        saveCredentials(ssid, password)

        Log.d(TAG, "Creating Wi-Fi Direct group with band: $band, IP preference: $ipPreference")
        statusText = "Creating group..."

        // Use WifiP2pConfig.Builder for API 29+ if SSID, password, or band is provided
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (normalizedSsid.isNotEmpty() || password.isNotEmpty() || band != "auto")) {
            try {
                val configBuilder = WifiP2pConfig.Builder()
                
                if (normalizedSsid.isNotEmpty()) {
                    configBuilder.setNetworkName(normalizedSsid)
                    Log.d(TAG, "Setting network name: $normalizedSsid")
                }
                
                if (password.isNotEmpty()) {
                    configBuilder.setPassphrase(password)
                    Log.d(TAG, "Setting passphrase")
                }
                
                // Set band if specified (API 29+)
                if (band != "auto") {
                    try {
                        val bandValue = when (band) {
                            "2.4" -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                            "5" -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                            else -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
                        }
                        configBuilder.setGroupOperatingBand(bandValue)
                        Log.d(TAG, "Setting band to: $band GHz")
                    } catch (e: UnsupportedOperationException) {
                        Log.w(TAG, "Band selection not supported on this device: ${e.message}")
                        statusText = "Band selection not supported. Creating with auto..."
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Invalid band parameter: ${e.message}")
                        statusText = "Invalid band selection. Creating with auto..."
                    }
                }
                
                val config = configBuilder.build()
                
                wifiP2pManager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Group created successfully with custom config")
                        statusText = "Group created! Requesting group info..."
                        // Clear validation errors after successful creation
                        ssidError = ""
                        passphraseError = ""
                        requestGroupInfo()
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Failed to create group: $reason")
                        statusText = "Failed to create group (code: $reason)"
                    }
                })
            } catch (e: IllegalArgumentException) {
                // If custom config fails due to invalid parameters, fall back to default
                Log.w(TAG, "Custom config rejected by system, falling back to default: ${e.message}")
                statusText = "Custom credentials rejected by system. Creating with defaults..."
                
                wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Group created successfully with system defaults")
                        statusText = "Group created with system defaults! Requesting group info..."
                        ssidError = ""
                        passphraseError = ""
                        requestGroupInfo()
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Failed to create group with defaults: $reason")
                        statusText = "Failed to create group (code: $reason)"
                    }
                })
            } catch (e: UnsupportedOperationException) {
                // If band selection is not supported, fall back to default
                Log.w(TAG, "Band selection not supported, falling back to default: ${e.message}")
                statusText = "Band selection not supported. Creating with defaults..."
                
                wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Group created successfully with system defaults")
                        statusText = "Group created with system defaults! Requesting group info..."
                        ssidError = ""
                        passphraseError = ""
                        requestGroupInfo()
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Failed to create group with defaults: $reason")
                        statusText = "Failed to create group (code: $reason)"
                    }
                })
            }
        } else {
            // Fallback for API < 29 or when no SSID/password/band is provided
            wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group created successfully")
                    statusText = "Group created! Requesting group info..."
                    ssidError = ""
                    passphraseError = ""
                    requestGroupInfo()
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to create group: $reason")
                    statusText = "Failed to create group (code: $reason)"
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        if (!checkPermissions()) {
            statusText = "Missing required permissions"
            return
        }

        Log.d(TAG, "Starting peer discovery")
        statusText = "Discovering peers..."

        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery initiated")
                statusText = "Discovering peers..."
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to start peer discovery: $reason")
                statusText = "Failed to discover peers (code: $reason)"
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectToFirstPeer() {
        if (peersList.isEmpty()) {
            statusText = "No peers available. Discover peers first."
            return
        }

        val device = peersList.first()
        Log.d(TAG, "Connecting to peer: ${device.deviceName}")
        statusText = "Connecting to ${device.deviceName}..."

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection initiated")
                statusText = "Connecting to ${device.deviceName}..."
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to connect: $reason")
                statusText = "Failed to connect (code: $reason)"
            }
        })
    }

    private fun startProxyService() {
        val intent = Intent(this, ProxyServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText = "Proxy server started on port ${ProxyServerService.PROXY_PORT}"
    }

    @SuppressLint("MissingPermission") // Permission checked before Wi-Fi P2P operations
    private fun stopGroup() {
        if (!checkPermissions()) {
            statusText = "Missing required permissions"
            return
        }

        Log.d(TAG, "Stopping Wi-Fi Direct group")
        statusText = "Stopping group..."

        wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group removed successfully")
                isGroupOwner = false
                groupSsid = ""
                groupPassphrase = ""
                statusText = "Group stopped. Ready to create a new group."
                stopProxyService()
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to remove group: $reason")
                statusText = "Failed to stop group (code: $reason)"
            }
        })
    }

    private fun stopProxyService() {
        val intent = Intent(this, ProxyServerService::class.java)
        stopService(intent)
        Log.d(TAG, "Proxy service stopped")
    }
    
    private fun startVpnClient() {
        Log.d(TAG, "startVpnClient() called")
        
        // Auto-detect gateway IP from active Wi-Fi connection
        val gatewayIp = detectGatewayIp()
        
        if (gatewayIp.isEmpty()) {
            val message = "Cannot start VPN: No gateway IP detected. Please connect to a Wi-Fi Direct network (SSID starting with DIRECT-)."
            statusText = message
            Toast.makeText(this, "No gateway IP detected. Connect to Wi-Fi Direct network first.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "startVpnClient aborted: No gateway IP detected")
            return
        }
        
        // Check if connected to a DIRECT- SSID
        if (!isConnectedToDirectSsid()) {
            val message = "Warning: Not connected to a DIRECT- SSID. Detected gateway: $gatewayIp. Proceeding anyway..."
            statusText = message
            Toast.makeText(this, "Warning: Not connected to DIRECT- SSID. Proceeding with detected gateway...", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Not connected to DIRECT- SSID, but proceeding with gateway: $gatewayIp")
        }
        
        // Update detected gateway and proxy host address
        detectedGatewayIp = gatewayIp
        proxyHostAddress = gatewayIp
        
        Log.d(TAG, "Starting VPN client with auto-detected proxy: $proxyHostAddress (SOCKS5:${ProxyServerService.SOCKS5_PORT})")
        
        // Check if VPN permission is needed
        val vpnIntent = android.net.VpnService.prepare(this)
        if (vpnIntent != null) {
            // Need to request VPN permission
            Log.d(TAG, "VPN permission required. Launching permission dialog...")
            statusText = "Requesting VPN permission..."
            Toast.makeText(this, "VPN permission required. Please grant permission in the dialog.", Toast.LENGTH_LONG).show()
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            // Permission already granted, start VPN directly
            Log.d(TAG, "VPN permission already granted. Starting VPN service directly...")
            Toast.makeText(this, "Starting VPN client...", Toast.LENGTH_SHORT).show()
            startVpnProxyService()
        }
    }
    
    private fun startVpnProxyService() {
        try {
            val intent = Intent(this, VpnProxyService::class.java).apply {
                action = VpnProxyService.ACTION_START_VPN
                putExtra(VpnProxyService.EXTRA_PROXY_HOST, proxyHostAddress)
                // SOCKS5 port will be used automatically by VpnProxyService
            }
            
            Log.d(TAG, "Starting VPN foreground service with SOCKS5 proxy: $proxyHostAddress:${ProxyServerService.SOCKS5_PORT}")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            isVpnActive = true
            statusText = "VPN client started. All traffic routing through $proxyHostAddress (SOCKS5:${ProxyServerService.SOCKS5_PORT})"
            Toast.makeText(this, "VPN active! Traffic routing through SOCKS5 proxy.", Toast.LENGTH_LONG).show()
            Log.d(TAG, "VPN proxy service started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN foreground service", e)
            isVpnActive = false
            statusText = "Failed to start VPN service: ${e.message}"
            Toast.makeText(this, "Failed to start VPN: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun stopVpnClient() {
        Log.d(TAG, "stopVpnClient() called")
        
        val intent = Intent(this, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_STOP_VPN
        }
        startService(intent)
        
        isVpnActive = false
        statusText = "VPN client stopped"
        Toast.makeText(this, "VPN client stopped", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "VPN client stop requested")
    }

    private fun checkPermissions(): Boolean {
        val permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Auto-detect gateway IP from active Wi-Fi connection.
     * This is used to automatically find the host IP when connected to a Wi-Fi Direct group.
     * Typical P2P gateway is 192.168.49.1.
     * @return Gateway IP address as String, or empty string if not found
     */
    @SuppressLint("MissingPermission")
    private fun detectGatewayIp(): String {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // Get active network
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork == null) {
                Log.w(TAG, "No active network")
                return ""
            }
            
            // Check if it's a Wi-Fi connection
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                Log.w(TAG, "Active network is not Wi-Fi")
                return ""
            }
            
            // Get link properties to extract gateway
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            if (linkProperties == null) {
                Log.w(TAG, "No link properties available")
                return ""
            }
            
            // Get the default route (gateway)
            val routes = linkProperties.routes
            for (route in routes) {
                if (route.isDefaultRoute) {
                    val gateway = route.gateway
                    if (gateway is Inet4Address) {
                        val gatewayIp = gateway.hostAddress
                        if (gatewayIp.isNullOrEmpty()) {
                            Log.w(TAG, "Gateway found but hostAddress is null/empty - possible system networking issue")
                            return ""
                        }
                        Log.d(TAG, "Detected gateway IP: $gatewayIp")
                        return gatewayIp
                    }
                }
            }
            
            Log.w(TAG, "No default route/gateway found")
            return ""
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting gateway IP", e)
            return ""
        }
    }

    /**
     * Check if currently connected to a Wi-Fi Direct network (SSID starts with "DIRECT-")
     * @return true if connected to DIRECT- SSID, false otherwise
     */
    @SuppressLint("MissingPermission")
    private fun isConnectedToDirectSsid(): Boolean {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            
            if (wifiInfo != null) {
                // Android's WifiInfo.ssid typically wraps SSIDs in double quotes
                // Use removeSurrounding to safely remove quotes only if they exist on both sides
                val ssid = wifiInfo.ssid?.removeSurrounding("\"") ?: ""
                Log.d(TAG, "Current Wi-Fi SSID: $ssid")
                return ssid.startsWith("DIRECT-", ignoreCase = true)
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking SSID", e)
            return false
        }
    }

    /**
     * Update the detected gateway IP state variable.
     * This is called periodically to keep the UI updated with the current gateway.
     */
    private fun updateDetectedGatewayIp() {
        val gateway = detectGatewayIp()
        detectedGatewayIp = gateway
        if (gateway.isNotEmpty()) {
            Log.d(TAG, "Gateway IP updated: $gateway")
        }
    }

    /**
     * Load saved SSID and passphrase from SharedPreferences
     */
    private fun loadSavedCredentials() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        savedSsid = prefs.getString(PREF_SSID, "").orEmpty()
        savedPassphrase = prefs.getString(PREF_PASSPHRASE, "").orEmpty()
        Log.d(TAG, "Loaded saved credentials - SSID length: ${savedSsid.length}, Passphrase length: ${savedPassphrase.length}")
    }

    /**
     * Save SSID and passphrase to SharedPreferences
     */
    private fun saveCredentials(ssid: String, passphrase: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(PREF_SSID, ssid)
            putString(PREF_PASSPHRASE, passphrase)
            apply()
        }
        savedSsid = ssid
        savedPassphrase = passphrase
        Log.d(TAG, "Saved credentials - SSID length: ${ssid.length}, Passphrase length: ${passphrase.length}")
    }

    @SuppressLint("MissingPermission") // Permission checked in checkPermissions() before calling
    private fun requestGroupInfo() {
        wifiP2pManager.requestGroupInfo(channel) { group ->
            if (group != null) {
                val ssid = group.networkName ?: "N/A"
                val passphrase = group.passphrase ?: "N/A"
                
                groupSsid = ssid
                groupPassphrase = passphrase
                isGroupOwner = group.isGroupOwner
                
                Log.d(TAG, "Group info - SSID: $ssid, Passphrase: $passphrase, isGroupOwner: ${group.isGroupOwner}")
                statusText = "Group created!\nSSID: $ssid\nPassphrase: $passphrase\nStarting proxy server..."
                startProxyService()
            } else {
                Log.w(TAG, "Group info is null")
                statusText = "Group created but couldn't retrieve info. Starting proxy server..."
                startProxyService()
            }
        }
    }

    @SuppressLint("MissingPermission") // Permission checked before Wi-Fi P2P operations
    private fun updateGroupInfoInStatus(info: WifiP2pInfo) {
        isGroupOwner = info.isGroupOwner
        
        // Save proxy host address for VPN client
        proxyHostAddress = info.groupOwnerAddress?.hostAddress ?: ""
        Log.d(TAG, "updateGroupInfoInStatus - proxyHostAddress set to: $proxyHostAddress (isGroupOwner: $isGroupOwner)")
        
        // Detect IPv4 and IPv6 addresses
        detectIpAddresses(info)
        
        wifiP2pManager.requestGroupInfo(channel) { group ->
            val role = if (info.isGroupOwner) "Group Owner (Host)" else "Client"
            val address = info.groupOwnerAddress?.hostAddress ?: "unknown"
            
            if (group != null) {
                val ssid = group.networkName ?: "N/A"
                val passphrase = group.passphrase ?: "N/A"
                
                groupSsid = ssid
                groupPassphrase = passphrase
                
                statusText = buildConnectionStatusText(role, address, ssid, passphrase, info.isGroupOwner)
            } else {
                statusText = buildConnectionStatusText(role, address, "", "", info.isGroupOwner)
            }
        }
    }
    
    /**
     * Detect IPv4 and IPv6 addresses from network interfaces and connection info
     */
    private fun detectIpAddresses(info: WifiP2pInfo) {
        try {
            // Clear previous addresses
            ipv4Address = ""
            ipv6Address = ""
            
            // Get the group owner address first
            val goAddress = info.groupOwnerAddress
            if (goAddress != null) {
                when (goAddress) {
                    is Inet4Address -> {
                        ipv4Address = goAddress.hostAddress ?: ""
                        Log.d(TAG, "Group owner IPv4: $ipv4Address")
                    }
                    is Inet6Address -> {
                        ipv6Address = goAddress.hostAddress ?: ""
                        Log.d(TAG, "Group owner IPv6: $ipv6Address")
                    }
                }
            }
            
            // Try to find Wi-Fi Direct interface addresses
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // Look for p2p interfaces (Wi-Fi Direct typically uses p2p-wlan or p2p-p2p interfaces)
                if (networkInterface.name.contains("p2p", ignoreCase = true)) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        when (address) {
                            is Inet4Address -> {
                                if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                                    if (ipv4Address.isEmpty()) {
                                        ipv4Address = address.hostAddress ?: ""
                                        Log.d(TAG, "Detected IPv4 from p2p interface: $ipv4Address")
                                    }
                                }
                            }
                            is Inet6Address -> {
                                if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                                    if (ipv6Address.isEmpty()) {
                                        ipv6Address = address.hostAddress ?: ""
                                        Log.d(TAG, "Detected IPv6 from p2p interface: $ipv6Address")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting IP addresses", e)
        }
    }
    
    /**
     * Build connection status text with IP address information
     */
    private fun buildConnectionStatusText(role: String, primaryAddress: String, ssid: String, passphrase: String, isGroupOwner: Boolean): String {
        var text = "Connected as $role\nGroup owner IP: $primaryAddress"
        
        // Add IPv4/IPv6 information if available
        if (ipv4Address.isNotEmpty()) {
            text += "\nIPv4: $ipv4Address"
        }
        if (ipv6Address.isNotEmpty()) {
            text += "\nIPv6: $ipv6Address"
        }
        
        // Add SSID and passphrase if available
        if (ssid.isNotEmpty() && ssid != "N/A") {
            text += "\nSSID: $ssid"
        }
        if (passphrase.isNotEmpty() && passphrase != "N/A") {
            text += "\nPassphrase: $passphrase"
        }
        
        // Add proxy information
        if (isGroupOwner) {
            text += "\nHTTP Proxy: port ${ProxyServerService.PROXY_PORT}"
            text += "\nSOCKS5 Proxy: port ${ProxyServerService.SOCKS5_PORT}"
        } else {
            text += "\nUse HTTP proxy: $primaryAddress:${ProxyServerService.PROXY_PORT}"
            text += "\nOr SOCKS5: $primaryAddress:${ProxyServerService.SOCKS5_PORT}"
        }
        
        return text
    }

    // Callbacks from WifiDirectReceiver
    fun onWifiP2pStateChanged(enabled: Boolean) {
        isWifiP2pEnabled = enabled
        statusText = if (enabled) {
            "Wi-Fi P2P enabled"
        } else {
            "Wi-Fi P2P disabled. Please enable Wi-Fi."
        }
    }

    fun onPeersAvailable(peers: WifiP2pDeviceList) {
        val devices = peers.deviceList.toList()
        peersList.clear()
        peersList.addAll(devices)
        
        Log.d(TAG, "Peers available: ${devices.size}")
        statusText = if (devices.isEmpty()) {
            "No peers found"
        } else {
            "Found ${devices.size} peer(s): ${devices.joinToString { it.deviceName }}"
        }
    }

    @SuppressLint("MissingPermission")
    fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (info.groupFormed) {
            Log.d(TAG, "Connected as ${if (info.isGroupOwner) "Group Owner" else "Client"}")
            
            // Set proxyHostAddress for clients (non-group owners)
            if (!info.isGroupOwner) {
                proxyHostAddress = info.groupOwnerAddress?.hostAddress ?: ""
                Log.d(TAG, "Client connected - Set proxyHostAddress to: $proxyHostAddress")
            }
            
            updateGroupInfoInStatus(info)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDirectProxyScreen(
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

        // VPN Client section - only show when not group owner
        if (!isGroupOwner) {
            Spacer(modifier = Modifier.height(8.dp))
            
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

        Spacer(modifier = Modifier.height(16.dp))

        // Display current group info if available
        if (groupSsid.isNotEmpty() && groupSsid != "N/A") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
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
