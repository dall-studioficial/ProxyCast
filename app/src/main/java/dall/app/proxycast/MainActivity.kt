package dall.app.proxycast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dall.app.proxycast.ui.theme.ProxyCastTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Wi-Fi P2P
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
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
                        onCreateGroup = { ssid, password -> createGroup(ssid, password) },
                        onDiscoverPeers = { discoverPeers() },
                        onConnectToPeer = { connectToFirstPeer() }
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

    @SuppressLint("MissingPermission")
    private fun createGroup(ssid: String = "", password: String = "") {
        if (!checkPermissions()) {
            statusText = "Missing required permissions"
            return
        }

        Log.d(TAG, "Creating Wi-Fi Direct group")
        statusText = "Creating group..."

        // Use WifiP2pConfig.Builder for API 29+ if SSID or password is provided
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (ssid.isNotEmpty() || password.isNotEmpty())) {
            val configBuilder = WifiP2pConfig.Builder()
            
            if (ssid.isNotEmpty()) {
                configBuilder.setNetworkName(ssid)
                Log.d(TAG, "Setting network name: $ssid")
            }
            
            if (password.isNotEmpty()) {
                configBuilder.setPassphrase(password)
                Log.d(TAG, "Setting passphrase")
            }
            
            val config = configBuilder.build()
            
            wifiP2pManager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group created successfully with custom config")
                    statusText = "Group created! Requesting group info..."
                    requestGroupInfo()
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to create group: $reason")
                    statusText = "Failed to create group (code: $reason)"
                }
            })
        } else {
            // Fallback for API < 29 or when no SSID/password is provided
            wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group created successfully")
                    statusText = "Group created! Requesting group info..."
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

    @SuppressLint("MissingPermission") // Permission checked in checkPermissions() before calling
    private fun requestGroupInfo() {
        wifiP2pManager.requestGroupInfo(channel) { group ->
            if (group != null) {
                val ssid = group.networkName ?: "N/A"
                val passphrase = group.passphrase ?: "N/A"
                
                groupSsid = ssid
                groupPassphrase = passphrase
                
                Log.d(TAG, "Group info - SSID: $ssid, Passphrase: $passphrase")
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
        wifiP2pManager.requestGroupInfo(channel) { group ->
            val role = if (info.isGroupOwner) "Group Owner (Host)" else "Client"
            val address = info.groupOwnerAddress?.hostAddress ?: "unknown"
            
            if (group != null) {
                val ssid = group.networkName ?: "N/A"
                val passphrase = group.passphrase ?: "N/A"
                
                groupSsid = ssid
                groupPassphrase = passphrase
                
                statusText = "Connected as $role\nGroup owner IP: $address\nSSID: $ssid\nPassphrase: $passphrase"
            } else {
                statusText = "Connected as $role\nGroup owner IP: $address"
            }
            
            if (info.isGroupOwner) {
                statusText += "\nProxy running on port ${ProxyServerService.PROXY_PORT}"
            } else {
                statusText += "\nUse proxy: $address:${ProxyServerService.PROXY_PORT}"
            }
        }
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
            updateGroupInfoInStatus(info)
        }
    }
}

@Composable
fun WifiDirectProxyScreen(
    modifier: Modifier = Modifier,
    statusText: String,
    groupSsid: String,
    groupPassphrase: String,
    onCreateGroup: (String, String) -> Unit,
    onDiscoverPeers: () -> Unit,
    onConnectToPeer: () -> Unit
) {
    var ssidInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
            label = { Text("Network Name (SSID)") },
            placeholder = { Text("Optional - Leave empty for default") },
            singleLine = true,
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

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { onCreateGroup(ssidInput, passwordInput) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Create Group + Start Proxy (Host)")
        }

        Button(
            onClick = onDiscoverPeers,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Discover Peers (Client)")
        }

        Button(
            onClick = onConnectToPeer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Connect to First Peer")
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
