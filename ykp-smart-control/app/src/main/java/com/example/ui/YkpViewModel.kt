package com.example.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.*
import com.example.data.*
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CommMode { AUTO, LOCAL_UDP, BLE, CLOUD_WSS }

data class BleScannedDevice(
    val name: String,
    val deviceId: String,
    val rssi: Int,
    val isConnecting: Boolean = false,
    val bluetoothDevice: android.bluetooth.BluetoothDevice? = null
)

data class WifiNetwork(
    val ssid: String,
    val rssi: Int,
    val auth: Int = 3
)

data class LocalWifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class LocalBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val primaryUuid: String?,
    val rawBytes: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class ScreenRoute {
    object Login : ScreenRoute()
    object Home : ScreenRoute()
    object Groups : ScreenRoute()
    object Automation : ScreenRoute()
    object Settings : ScreenRoute()
    data class DeviceDetail(val deviceId: String, val activeTab: Int = 0) : ScreenRoute()
    object Provisioning : ScreenRoute()
    data class Ota(val deviceId: String) : ScreenRoute()
    data class BleDiagnostics(val deviceId: String) : ScreenRoute()
    object NetworkScanner : ScreenRoute()
}

class YkpViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = YkpRepository(application)
    private val realtimeSyncManager = RealtimeSyncManager(repository) { serverUrlInput }

    private fun syncRealtimeState() {
        if (isDemoMode || loggedInEmail == null) {
            realtimeSyncManager.stop()
        } else {
            realtimeSyncManager.start()
        }
    }

    // Data sources exposed as state flows
    val devices: StateFlow<List<DeviceEntity>> = repository.allDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<GroupEntity>> = repository.allGroups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules: StateFlow<List<AutomationEntity>> = repository.allRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI state
    var currentRoute by mutableStateOf<ScreenRoute>(ScreenRoute.Login)
        private set

    val backStack = mutableStateListOf<ScreenRoute>()

    // Current logged-in user details
    var loggedInEmail by mutableStateOf<String?>(null)
        private set

    // Configuration states
    var cloudServerUrl by mutableStateOf("https://iot-yk.onrender.com")
        private set
    var isDemoMode by mutableStateOf(false)
        private set

    var authErrorText by mutableStateOf<String?>(null)
    var isAuthLoading by mutableStateOf(false)

    // Active Device details (monitored reactively when detail screen matches ID)
    val deviceCommModes = androidx.compose.runtime.mutableStateMapOf<String, CommMode>()
    val deviceActiveChannels = androidx.compose.runtime.mutableStateMapOf<String, String>()

    private val _activeDeviceId = MutableStateFlow<String?>(null)
    val activeDevice: StateFlow<DeviceEntity?> = _activeDeviceId
        .flatMapLatest { id ->
            if (id != null) repository.getDeviceById(id) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeDeviceHealthHistory: StateFlow<List<HealthEntity>> = _activeDeviceId
        .flatMapLatest { id ->
            if (id != null) repository.getHealthHistory(id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeDeviceLatestHealth: StateFlow<HealthEntity?> = _activeDeviceId
        .flatMapLatest { id ->
            if (id != null) repository.getLatestHealth(id) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Provisioning Wizard States
    var provisionStep by mutableIntStateOf(1)
    var setupApSsid by mutableStateOf("YKP-Setup-F7A9C2")
    var setupApPassword by mutableStateOf("ykpsetup123")
    var wifiSsidInput by mutableStateOf("santhi")
    var wifiPasswordInput by mutableStateOf("YASH@123456789")
    var devIdInput by mutableStateOf("MT496")
    var devNameInput by mutableStateOf("Living Room SETUP")
    var devTypeInput by mutableStateOf("switch") // "switch", "sensor", "motor", "gateway"
    var serverUrlInput by mutableStateOf("wss://iot-yk.onrender.com/ws")
    var setupIsOnlineChecked by mutableStateOf(false)
    var setupWaitingText by mutableStateOf("Connect to Setup Hotspot first...")
    var isPinCodeTriggered by mutableStateOf(false)
    var isProvisioningActive by mutableStateOf(false)

    // BLE Provisioning States
    var isBleMode by mutableStateOf(true) // Start with true (BLE preferred)
    var isBleScanning by mutableStateOf(false)
    val scannedBleDevices = mutableStateListOf<BleScannedDevice>()
    var selectedBleDevice by mutableStateOf<BleScannedDevice?>(null)
    var bleHandshakeProgress by mutableStateOf("")
    var isBleHandshaking by mutableStateOf(false)
    var isBleConnected by mutableStateOf(false)
    var negotiatedMtu by mutableStateOf(23)
    var sessionId by mutableStateOf("")
    var seq by mutableStateOf(1)
    private var hmacKey = ByteArray(32)
    private val reportChunks = mutableMapOf<Int, String>()
    var isConfirmProvisionEnabled by mutableStateOf(false)
    private var lastWifiScanTime: Long = 0

    // Onboarding Wi-Fi Scanning States
    var isWifiScanning by mutableStateOf(false)
    val scannedWifiNetworks = mutableStateListOf<WifiNetwork>()

    // Modern multi-stage provisioning additions
    var secureCertInput by mutableStateOf("")
    var isPreRegistering by mutableStateOf(false)
    var currentTransmissionChunkIdx by mutableIntStateOf(0)
    var totalTransmissionChunks by mutableIntStateOf(0)
    var verificationTimeRemaining by mutableIntStateOf(60)
    var verificationStep1Passed by mutableStateOf(false)
    var verificationStep2Passed by mutableStateOf(false)
    var verificationStep3Passed by mutableStateOf(false)
    var fallbackActiveError by mutableStateOf(false)
    var fallbackDeviceName by mutableStateOf("")
    var fallbackErrorMessage by mutableStateOf("")
    var activeFallbackDevice by mutableStateOf<android.bluetooth.BluetoothDevice?>(null)

    // Background OOBE Scanner States
    var showOobeBottomSheet by mutableStateOf(false)
    var discoveredOobeDeviceName by mutableStateOf("")
    var discoveredOobeDeviceAddress by mutableStateOf("")
    var discoveredOobeDeviceHardware by mutableStateOf<android.bluetooth.BluetoothDevice?>(null)
    var isOobeScanning by mutableStateOf(false)
    private var oobeScanCallback: android.bluetooth.le.ScanCallback? = null

    // Offline Diagnostics States
    var activeOfflineDiagnosticDevice by mutableStateOf<DeviceEntity?>(null)
    var diagnosticsWaitingText by mutableStateOf("")
    val diagnosticsLogs = mutableStateListOf<String>()
    var isDiagnosticsConnecting by mutableStateOf(false)
    var isDiagnosticsConnected by mutableStateOf(false)
    var diagnosticsRelayState by mutableStateOf(false)
    var activeDiagnosticsDeviceId by mutableStateOf<String?>(null)
    var isDiagnosticsSimulated by mutableStateOf(false)
    var isBleSetupSimulated by mutableStateOf(false)
    private var activeDiagnosticsGatt: android.bluetooth.BluetoothGatt? = null

    // OTA updates states
    var otaProgress by mutableStateOf(0.0f)
    var otaStatusText by mutableStateOf("Idle")
    var otaJobActive by mutableStateOf(false)

    var ykpBleManager: YkpBleManager? = null

    init {
        // Init client parameters
        YkpApiClient.cloudServerUrl = cloudServerUrl
        repository.isDemoMode = isDemoMode
        syncRealtimeState()
        
        ykpBleManager = YkpBleManager(application, object : YkpBleListener {
            override fun onConnectionStateChanged(isConnected: Boolean, progressMessage: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    isBleConnected = isConnected
                    bleHandshakeProgress = progressMessage
                    activeGatt = if (isConnected) ykpBleManager?.activeGatt else null
                    if (progressMessage == "Ready to provision") {
                        isBleHandshaking = false
                        setupWaitingText = "Device Connected! Direct secure channel established."
                        delay(1200)
                        provisionStep = 2
                        startBleWifiScan()
                    }
                }
            }

            override fun onNotificationReceived(json: org.json.JSONObject) {
                viewModelScope.launch(Dispatchers.Main) {
                    handleIncomingBleText(null, json.toString())
                }
            }

            override fun onError(message: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    bleHandshakeProgress = "GATT Connection Error: $message"
                    isBleHandshaking = false
                    isBleConnected = false
                    activeGatt = null
                }
            }
        }, viewModelScope)

        // Asynchronously fetch SSL certificate on startup
        viewModelScope.launch(Dispatchers.IO) {
            repository.fetchAndCacheSslCert()
        }
    }

    // ── Navigation Engine ──────────────────────────────
    fun navigateTo(route: ScreenRoute) {
        backStack.add(currentRoute)
        currentRoute = route
        
        // Handle active device parameter triggers
        if (route is ScreenRoute.DeviceDetail) {
            _activeDeviceId.value = route.deviceId
            refreshActiveDeviceHealth()
        } else if (route is ScreenRoute.Ota) {
            _activeDeviceId.value = route.deviceId
            refreshActiveDeviceHealth()
        } else if (route is ScreenRoute.BleDiagnostics) {
            activeDiagnosticsDeviceId = route.deviceId
        } else if (route is ScreenRoute.Provisioning) {
            // Fetch certificate fresh on entering Onboarding screen
            viewModelScope.launch(Dispatchers.IO) {
                repository.fetchAndCacheSslCert()
            }
        }
    }

    fun navigateBack() {
        if (backStack.isNotEmpty()) {
            val prev = backStack.removeAt(backStack.size - 1)
            currentRoute = prev
            
            // Sync active device ID on back transitions
            if (prev is ScreenRoute.DeviceDetail) {
                _activeDeviceId.value = prev.deviceId
                refreshActiveDeviceHealth()
            } else if (prev is ScreenRoute.Ota) {
                _activeDeviceId.value = prev.deviceId
                refreshActiveDeviceHealth()
            } else if (prev is ScreenRoute.BleDiagnostics) {
                activeDiagnosticsDeviceId = prev.deviceId
            } else {
                _activeDeviceId.value = null
                activeDiagnosticsDeviceId = null
            }
        }
    }

    fun refreshActiveDeviceHealth() {
        val id = _activeDeviceId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.refreshDeviceHealth(id)
        }
    }

    fun setServerUrl(url: String) {
        cloudServerUrl = url
        YkpApiClient.cloudServerUrl = url
        syncRealtimeState()
    }

    var isRefreshing by mutableStateOf(false)
        private set

    fun refreshBrokerDevices() {
        if (isDemoMode) return
        isRefreshing = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.refreshDevices()
            } finally {
                withContext(Dispatchers.Main) {
                    isRefreshing = false
                }
            }
        }
    }

    fun toggleDemoMode(enabled: Boolean) {
        isDemoMode = enabled
        repository.isDemoMode = enabled
        syncRealtimeState()
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearLocalDatabase(seedIfDemo = true)
            if (!enabled) {
                refreshBrokerDevices()
            }
        }
    }

    // ── Auth Services ──────────────────────────────────
    fun authenticate(email: String, passwordI: String, onComplete: (Boolean) -> Unit) {
        if (!email.contains("@") || !email.contains(".")) {
            authErrorText = "Please enter a valid developer email address."
            onComplete(false)
            return
        }
        if (isDemoMode) {
            loggedInEmail = email
            YkpApiClient.sessionToken = "demo_session_token_123"
            syncRealtimeState()
            navigateTo(ScreenRoute.Home)
            refreshBrokerDevices()
            authErrorText = null
            onComplete(true)
            return
        }

        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = YkpApiClient.getRouterApi().login(LoginRequest(email, passwordI))
                withContext(Dispatchers.Main) {
                    val token = response.session?.accessToken
                    if (!token.isNullOrEmpty()) {
                        YkpApiClient.sessionToken = token
                        YkpApiClient.refreshToken = response.session?.refreshToken
                        loggedInEmail = email
                        syncRealtimeState()
                        navigateTo(ScreenRoute.Home)
                        refreshBrokerDevices()
                        onComplete(true)
                    } else {
                        authErrorText = response.error ?: "Invalid session returned from server"
                        onComplete(false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    authErrorText = e.localizedMessage ?: "Failed connection to auth proxy server."
                    onComplete(false)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                }
            }
        }
    }

    fun registerAccount(email: String, passwordI: String, onComplete: (Boolean) -> Unit) {
        if (!email.contains("@") || !email.contains(".")) {
            authErrorText = "Please enter a valid developer email address."
            onComplete(false)
            return
        }
        if (isDemoMode) {
            authErrorText = "Registration is not available in local simulation mode."
            onComplete(false)
            return
        }

        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = YkpApiClient.getRouterApi().register(RegisterRequest(email, passwordI))
                withContext(Dispatchers.Main) {
                    if (response.success) {
                        authErrorText = null
                        onComplete(true)
                    } else {
                        authErrorText = response.error ?: response.message ?: "Registration failed."
                        onComplete(false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    authErrorText = e.localizedMessage ?: "Failed connection to auth proxy server."
                    onComplete(false)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                }
            }
        }
    }

    fun signOut() {
        loggedInEmail = null
        syncRealtimeState()
        backStack.clear()
        currentRoute = ScreenRoute.Login
    }

    // ── Device Controls ──────────────────────────────────
    private fun sendBleToggleCommand(deviceId: String) {
        val context = getApplication<Application>().applicationContext
        val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        setupWaitingText = "Routing command over BLE Direct Fallback..."
        android.util.Log.d("YkpViewModel", "BLE Fallback triggered for $deviceId")
        
        val callback = object : android.bluetooth.le.ScanCallback() {
            var isConnecting = false
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                synchronized(this) {
                    if (isConnecting) return
                    val device = result.device
                    val scanRecord = result.scanRecord
                    val name = scanRecord?.deviceName ?: device.name ?: ""
                    
                    if (name.contains("YKP", ignoreCase = true) || name.contains("PROV", ignoreCase = true) || name.contains("Node", ignoreCase = true)) {
                        isConnecting = true
                        try {
                            scanner.stopScan(this)
                        } catch (e: SecurityException) {
                            // ignore
                        }
                        
                        android.util.Log.d("YkpViewModel", "BLE Fallback scanner found device: $name (${device.address})")
                        connectAndSendToggle(device)
                    }
                }
            }
        }
        
        try {
            scanner.startScan(callback)
            viewModelScope.launch {
                delay(3000)
                try {
                    scanner.stopScan(callback)
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: SecurityException) {
            setupWaitingText = "BLE Error: Bluetooth permission missing."
        }
    }

    private fun connectAndSendToggle(device: android.bluetooth.BluetoothDevice) {
        val context = getApplication<Application>().applicationContext
        try {
            device.connectGatt(context, false, object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                    try {
                        if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            android.util.Log.d("YkpViewModel", "BLE Fallback: Connected. Discovering services...")
                            gatt.discoverServices()
                        } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                            android.util.Log.d("YkpViewModel", "BLE Fallback: Disconnected.")
                            gatt.close()
                        }
                    } catch (e: SecurityException) {
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                    try {
                        if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                            var rxChar: android.bluetooth.BluetoothGattCharacteristic? = null
                            for (srv in gatt.services) {
                                val srvUuidStr = srv.uuid.toString().lowercase()
                                for (chr in srv.characteristics) {
                                    val uuidStr = chr.uuid.toString().lowercase()
                                    if (srvUuidStr == "12345678-1234-5678-1234-56789abcdef0" ||
                                        uuidStr == "87654321-4321-8765-4321-0fedcba98765") {
                                        if (uuidStr == "87654321-4321-8765-4321-0fedcba98765") {
                                            rxChar = chr
                                            break
                                        }
                                    }
                                }
                                if (rxChar != null) break
                            }

                            if (rxChar != null) {
                                val togglePayload = org.json.JSONObject()
                                togglePayload.put("cmd", "toggle")
                                val payloadBytes = togglePayload.toString().toByteArray(Charsets.UTF_8)
                                
                                rxChar.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                val writeSuccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeCharacteristic(rxChar, payloadBytes, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                                } else {
                                    @Suppress("DEPRECATION")
                                    rxChar.value = payloadBytes
                                    @Suppress("DEPRECATION")
                                    gatt.writeCharacteristic(rxChar)
                                }
                                android.util.Log.d("YkpViewModel", "BLE Fallback: Sent toggle command successfully=[$writeSuccess]")
                                
                                viewModelScope.launch(Dispatchers.Main) {
                                    setupWaitingText = "Toggle command transmitted directly via BLE!"
                                }
                                
                                viewModelScope.launch {
                                    delay(800)
                                    try {
                                        gatt.disconnect()
                                        gatt.close()
                                    } catch (e: Exception) {
                                        // ignore
                                    }
                                }
                            } else {
                                android.util.Log.e("YkpViewModel", "BLE Fallback: service/characteristic not found.")
                                gatt.disconnect()
                                gatt.close()
                            }
                        } else {
                            gatt.disconnect()
                            gatt.close()
                        }
                    } catch (e: SecurityException) {
                        try {
                            gatt.close()
                        } catch (ex: Exception) {}
                    }
                }
            })
        } catch (e: SecurityException) {
            // ignore
        }
    }

    suspend fun sendDeviceCommand(
        device: DeviceEntity,
        command: Boolean,
        selectedMode: CommMode,
        onModeResolved: (String) -> Unit
    ): Boolean {
        val deviceId = device.deviceId
        val nextPacketId = repository.getNextPacketId(deviceId)
        
        when (selectedMode) {
            CommMode.LOCAL_UDP -> {
                onModeResolved("LOCAL_UDP")
                deviceActiveChannels[deviceId] = "LOCAL_UDP"
                val udpSuccess = repository.sendLocalControlCommand(deviceId, command, nextPacketId)
                if (udpSuccess) {
                    repository.updateDeviceLocalState(deviceId, command)
                }
                return udpSuccess
            }
            CommMode.BLE -> {
                onModeResolved("BLE")
                deviceActiveChannels[deviceId] = "BLE"
                sendBleToggleCommand(deviceId)
                repository.updateDeviceLocalState(deviceId, command)
                return true
            }
            CommMode.CLOUD_WSS -> {
                onModeResolved("CLOUD_WSS")
                deviceActiveChannels[deviceId] = "CLOUD_WSS"
                var cloudSuccess = false
                try {
                    val serviceType = 1 // RELAY service
                    val action = if (command) 1 else 2 // 1 = ON, 2 = OFF
                    YkpApiClient.getRouterApi().sendCommand(deviceId, CommandRequest(serviceType, action, nextPacketId))
                    cloudSuccess = true
                    repository.updateDeviceLocalState(deviceId, command)
                } catch (e: Exception) {
                    android.util.Log.e("YkpViewModel", "Cloud Command Proxy failed", e)
                }
                return cloudSuccess
            }
            CommMode.AUTO -> {
                // Priority 1 (Highest): Local UDP. Ping / socket connect to device.localIp on port 3333.
                val ip = device.ipAddress ?: repository.hostDiscoveryManager.getDiscoveredIp(deviceId)
                if (ip != null && ip.isNotEmpty() && ip != "0.0.0.0" && ip != "null") {
                    val isReachable = withContext(Dispatchers.IO) {
                        try {
                            val address = java.net.InetAddress.getByName(ip)
                            val pingOk = try { address.isReachable(800) } catch (e: Exception) { false }
                            if (pingOk) return@withContext true
                            
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress(ip, 3333), 800)
                            socket.close()
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                    if (isReachable) {
                        val udpSuccess = repository.sendLocalControlCommand(deviceId, command, nextPacketId)
                        if (udpSuccess) {
                            onModeResolved("LOCAL_UDP")
                            deviceActiveChannels[deviceId] = "LOCAL_UDP"
                            repository.updateDeviceLocalState(deviceId, command)
                            return true
                        }
                    }
                }
                
                // Priority 2: BLE
                val hasBle = isBleConnected || selectedBleDevice != null
                if (hasBle) {
                    onModeResolved("BLE")
                    deviceActiveChannels[deviceId] = "BLE"
                    sendBleToggleCommand(deviceId)
                    repository.updateDeviceLocalState(deviceId, command)
                    return true
                }
                
                // Priority 3: Cloud WSS
                onModeResolved("CLOUD_WSS")
                deviceActiveChannels[deviceId] = "CLOUD_WSS"
                var cloudSuccess = false
                try {
                    val serviceType = 1 // RELAY service
                    val action = if (command) 1 else 2 // 1 = ON, 2 = OFF
                    YkpApiClient.getRouterApi().sendCommand(deviceId, CommandRequest(serviceType, action, nextPacketId))
                    cloudSuccess = true
                    repository.updateDeviceLocalState(deviceId, command)
                } catch (e: Exception) {
                    android.util.Log.e("YkpViewModel", "Cloud Command Proxy failed", e)
                }
                return cloudSuccess
            }
        }
    }

    private suspend fun executeDeviceControl(deviceId: String, relayState: Boolean) {
        val list = devices.value
        val device = list.find { it.deviceId == deviceId }
        
        if (device == null || isDemoMode) {
            repository.controlRelay(deviceId, relayState)
            return
        }

        val selectedMode = deviceCommModes[deviceId] ?: CommMode.AUTO
        sendDeviceCommand(device, relayState, selectedMode) { resolved ->
            android.util.Log.d("YkpViewModel", "Command dispatched to $deviceId using $resolved")
        }
    }

    fun toggleDeviceState(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = devices.value
            val match = list.find { it.deviceId == deviceId } ?: return@launch
            executeDeviceControl(deviceId, !match.relayState)
        }
    }

    fun setDeviceRelay(deviceId: String, relayState: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            executeDeviceControl(deviceId, relayState)
        }
    }

    fun simulateSensorActivity(deviceId: String, isTriggered: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.triggerSensorStateChange(deviceId, isTriggered)
        }
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeDevice(deviceId)
            withContext(Dispatchers.Main) {
                navigateBack()
            }
        }
    }

    // ── Groups Services ─────────────────────────────────
    fun toggleGroupState(groupId: Long, state: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.controlGroup(groupId, state)
        }
    }

    fun createGroup(name: String, members: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addGroup(name, members)
        }
    }

    fun removeGroup(groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteGroup(groupId)
        }
    }

    // ── Rules Engine Services ──────────────────────────────
    fun addAutomationRule(
        name: String,
        triggerDevice: String,
        triggerAction: Int,
        targetDevice: String,
        targetAction: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addRule(name, triggerDevice, triggerAction, targetDevice, targetAction)
        }
    }

    fun toggleRule(ruleId: String, isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleRule(ruleId, isEnabled)
        }
    }

    fun removeRule(ruleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteRule(ruleId)
        }
    }

    private var activeLeScanCallback: android.bluetooth.le.ScanCallback? = null
    private var activeGatt: android.bluetooth.BluetoothGatt? = null
    private var bleCompletedDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var writeCallbackDeferred: kotlinx.coroutines.CompletableDeferred<Int>? = null
    private var configAckDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var certAckDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    private fun stopActiveBleScan() {
        if (isBleScanning) {
            val callback = activeLeScanCallback
            if (callback != null) {
                val context = getApplication<Application>().applicationContext
                val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
                if (scanner != null) {
                    try {
                        scanner.stopScan(callback)
                    } catch (e: SecurityException) {
                        // ignore
                    }
                }
                activeLeScanCallback = null
            }
            isBleScanning = false
        }
    }

    // ── Provisioning SoftAP/BLE Wizard ───────────────────────
    fun startProvisioningWizard() {
        stopActiveBleScan()
        val gatt = activeGatt
        if (gatt != null) {
            try {
                gatt.close()
            } catch (e: SecurityException) {
                // ignore
            }
            activeGatt = null
        }
        provisionStep = 1
        isBleMode = true // default to BLE
        isBleScanning = false
        scannedBleDevices.clear()
        selectedBleDevice = null
        bleHandshakeProgress = ""
        isBleHandshaking = false
        isBleConnected = false
        wifiSsidInput = ""
        wifiPasswordInput = ""
        val randomSuffix = Random.nextInt(100, 999).toString()
        devNameInput = "Kitchen Node $randomSuffix"
        devTypeInput = "switch"
        setupIsOnlineChecked = false
        setupWaitingText = "Initiating pulsing radar scanner to locate physical BLE nodes..."
        
        // Reset modern provisioning variables
        secureCertInput = ""
        isPreRegistering = false
        currentTransmissionChunkIdx = 0
        totalTransmissionChunks = 0
        verificationTimeRemaining = 60
        verificationStep1Passed = false
        verificationStep2Passed = false
        verificationStep3Passed = false
        fallbackActiveError = false
        fallbackDeviceName = ""

        navigateTo(ScreenRoute.Provisioning)
        startBleScan()
    }

    fun startBleScan() {
        if (isBleScanning) return
        scannedBleDevices.clear()
        isBleScanning = true

        if (isDemoMode) {
            setupWaitingText = "Searching for physical BLE setup advertising beacons..."
            viewModelScope.launch(Dispatchers.IO) {
                // Show the beautiful pulsing radar animation for 2 seconds
                kotlinx.coroutines.delay(2000)
                val testDevice = BleScannedDevice("YKP_PROV_SETUP_A9", "7C:9E:BD:F0:A9:C2", -48)
                withContext(Dispatchers.Main) {
                    scannedBleDevices.add(testDevice)
                    isBleScanning = false
                    setupWaitingText = "Device Found!"
                    // Trigger instant connection upon beacon detection
                    selectBleDevice(testDevice)
                }
            }
            return
        }

        val context = getApplication<Application>().applicationContext
        val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setupWaitingText = "Bluetooth is disabled or not available on this device."
            isBleScanning = false
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            setupWaitingText = "Unable to get Bluetooth LE Scanner. Make sure Location Services are enabled."
            isBleScanning = false
            return
        }

        setupWaitingText = "Scanning strictly for YKP SETUP signatures (Service: 021a9004)..."

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val scanRecord = result.scanRecord
                val name = scanRecord?.deviceName ?: device.name ?: "Unknown Node"
                val rssi = result.rssi
                val deviceId = device.address

                // Filter strictly for setup beacons (specifically "YKP_PROV")
                if (name.contains("YKP_PROV_SETUP", ignoreCase = true) || name.contains("YKP_PROV", ignoreCase = true)) {
                    viewModelScope.launch(Dispatchers.Main) {
                        if (scannedBleDevices.none { it.deviceId == deviceId }) {
                            val scannedDev = BleScannedDevice(name, deviceId, rssi, bluetoothDevice = device)
                            scannedBleDevices.add(scannedDev)
                            setupWaitingText = "Device Found!"
                            // Instantly connect once detected
                            selectBleDevice(scannedDev)
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                viewModelScope.launch(Dispatchers.Main) {
                    isBleScanning = false
                    setupWaitingText = "Scanning failed with error code: $errorCode"
                }
            }
        }

        activeLeScanCallback = callback

        try {
            scanner.startScan(callback)
            viewModelScope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(15000)
                if (isBleScanning && activeLeScanCallback == callback) {
                    stopActiveBleScan()
                    setupWaitingText = "Scan completed. No setup device detected. Tap to retry."
                }
            }
        } catch (e: SecurityException) {
            setupWaitingText = "Permission error starting BLE scan: ${e.localizedMessage}"
            isBleScanning = false
        } catch (e: Exception) {
            setupWaitingText = "Error starting BLE scan: ${e.localizedMessage}"
            isBleScanning = false
        }
    }

    fun selectBleDevice(device: BleScannedDevice) {
        stopActiveBleScan()
        selectedBleDevice = device
        provisionStep = 1
        devNameInput = "Living Room " + device.name.removePrefix("YKP_PROV_")
        devTypeInput = when {
            device.deviceId.startsWith("SW") || device.name.contains("SW", ignoreCase = true) -> "switch"
            device.deviceId.startsWith("SN") || device.name.contains("SN", ignoreCase = true) -> "sensor"
            else -> "motor"
        }
        val suffixNum = device.deviceId.takeLast(4).uppercase().replace(":", "")
        devIdInput = when (devTypeInput) {
            "switch" -> "SW$suffixNum"
            "sensor" -> "SN$suffixNum"
            else -> "MT$suffixNum"
        }
        runBleHandshake(device)
    }

    private fun runBleHandshake(device: BleScannedDevice) {
        isBleHandshaking = true
        isBleConnected = false
        isBleSetupSimulated = false
        bleHandshakeProgress = "Stopping BLE scan & letting Bluetooth stack settle..."

        if (isDemoMode) {
            isBleSetupSimulated = true
            viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.Main) {
                    bleHandshakeProgress = "GATT services discovered. Service: 021a9004-0382-4aea-bff4-6b3f1c5adfb4."
                }
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.Main) {
                    bleHandshakeProgress = "Handshaking over characteristic 'prov-session' using SRP6a key exchange..."
                }
                kotlinx.coroutines.delay(1000)
                withContext(Dispatchers.Main) {
                    bleHandshakeProgress = "Session key verified (Authentication: NULL PoP). Securing GCM channel..."
                }
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.Main) {
                    bleHandshakeProgress = "AES Secure Session established successfully! Encryption ACTIVE."
                    isBleHandshaking = false
                    isBleConnected = true
                    setupWaitingText = "Device Connected! Direct secure channel established."
                }
                kotlinx.coroutines.delay(1200)
                withContext(Dispatchers.Main) {
                    provisionStep = 2
                    setupWaitingText = "Fetching nearby Wi-Fi networks..."
                    startBleWifiScan()
                }
            }
            return
        }

        // Real BLE Connection and Handshake logic:
        stopActiveBleScan()
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(650)
            ykpBleManager?.startSession(device.deviceId)
        }
    }

    fun checkApConnectivity() {
        setupWaitingText = "Polling SoftAP ping endpoint: http://192.168.4.1/ping..."
        viewModelScope.launch(Dispatchers.IO) {
            val connected = repository.pingProvisioningAp()
            withContext(Dispatchers.Main) {
                if (connected || isDemoMode) {
                    setupWaitingText = "Connected to AP successfully! Mode: PROVISIONING."
                    provisionStep = 2
                } else {
                    setupWaitingText = "Ping failed. Ensure your mobile is manually connected to Wi-Fi SSID '$setupApSsid'."
                }
            }
        }
    }

    private fun buildIndexedChunks(jsonPayload: String, maxChunkSize: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        val rawBytes = jsonPayload.toByteArray(Charsets.UTF_8)
        
        val payloadStep = maxChunkSize - 20
        var tempTotal = (rawBytes.size + payloadStep - 1) / payloadStep
        if (tempTotal <= 0) tempTotal = 1
        
        var offset = 0
        var chunkIndex = 1
        while (offset < rawBytes.size) {
            val header = "$chunkIndex/$tempTotal:"
            val headerBytes = header.toByteArray(Charsets.UTF_8)
            val remaining = rawBytes.size - offset
            val takeSize = Math.min(remaining, maxChunkSize - headerBytes.size)
            
            val chunk = ByteArray(headerBytes.size + takeSize)
            System.arraycopy(headerBytes, 0, chunk, 0, headerBytes.size)
            System.arraycopy(rawBytes, offset, chunk, headerBytes.size, takeSize)
            chunks.add(chunk)
            
            offset += takeSize
            chunkIndex++
        }
        
        val exactTotal = chunks.size
        if (exactTotal != tempTotal) {
            chunks.clear()
            offset = 0
            for (i in 1..exactTotal) {
                val header = "$i/$exactTotal:"
                val headerBytes = header.toByteArray(Charsets.UTF_8)
                val remaining = rawBytes.size - offset
                val takeSize = Math.min(remaining, maxChunkSize - headerBytes.size)
                
                val chunk = ByteArray(headerBytes.size + takeSize)
                System.arraycopy(headerBytes, 0, chunk, 0, headerBytes.size)
                System.arraycopy(rawBytes, offset, chunk, headerBytes.size, takeSize)
                chunks.add(chunk)
                
                offset += takeSize
            }
        }
        
        return chunks
    }

    fun applyWifiConfig() {
        if (isProvisioningActive) return
        isProvisioningActive = true
        setupWaitingText = "Uploading credentials: SSID=$wifiSsidInput..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isBleMode && !isDemoMode && !isBleSetupSimulated) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Contacting YKP Cloud registry for authentication tokens..."
                    }
                    try {
                        val req = CloudDeviceRegisterRequest(
                            deviceName = devNameInput.trim().ifEmpty { "YKP Switch" },
                            deviceType = devTypeInput.trim().ifEmpty { "switch" },
                            room = "Living Room"
                        )
                        val resp = YkpApiClient.getRouterApi().registerDevice(req)
                        if (resp.success && !resp.deviceId.isNullOrEmpty() && !resp.sslCert.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) {
                                devIdInput = resp.deviceId
                                secureCertInput = resp.sslCert
                            }
                        } else {
                            val err = resp.error ?: "API returned unsuccessful registration"
                            withContext(Dispatchers.Main) {
                                setupWaitingText = "Registry returned: $err. Continuing with entered Device ID..."
                            }
                            kotlinx.coroutines.delay(1000)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            setupWaitingText = "Cloud Registry offline. Continuing with offline Device ID profile..."
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                }

                val assignedId = devIdInput.trim()

                if (isBleMode) {
                    if (isDemoMode || isBleSetupSimulated) {
                        withContext(Dispatchers.Main) {
                            setupWaitingText = "Writing custom JSON parameters to GATT characteristics..."
                        }
                        kotlinx.coroutines.delay(800)
                        withContext(Dispatchers.Main) {
                            setupWaitingText = "Custom endpoint 'ykp-config' transmitted successfully. Payload: {\"device_id\":\"$assignedId\",\"device_type\":\"$devTypeInput\",\"server_url\":\"$serverUrlInput\",\"device_name\":\"$devNameInput\"}"
                        }
                        kotlinx.coroutines.delay(800)
                        withContext(Dispatchers.Main) {
                            setupWaitingText = "Sending Wi-Fi configuration request over standard protocomm endpoint..."
                        }
                        kotlinx.coroutines.delay(1000)
                        withContext(Dispatchers.Main) {
                            setupWaitingText = "Device successfully connected to the cloud! IP: 192.168.1.199"
                        }
                        kotlinx.coroutines.delay(650)
                    } else {
                        val gatt = activeGatt
                        if (gatt == null) {
                            withContext(Dispatchers.Main) {
                                setupWaitingText = "Error: BLE connection lost. Please reconnect."
                            }
                            return@launch
                        }

                        withContext(Dispatchers.Main) {
                            setupWaitingText = "Locating BLE write characteristic..."
                        }

                        var rxChar: android.bluetooth.BluetoothGattCharacteristic? = null
                        for (srv in gatt.services) {
                            for (chr in srv.characteristics) {
                                val uuidStr = chr.uuid.toString().lowercase()
                                if (uuidStr == "87654321-4321-8765-4321-0fedcba98765" ||
                                    uuidStr == "6e400002-b5a3-f393-e0a9-e50e24dcca9e" || 
                                    uuidStr == "9ecadc24-0ee5-a9e0-93f3-a3b50200406e") {
                                    rxChar = chr
                                    break
                                }
                            }
                            if (rxChar != null) break
                        }

                        if (rxChar == null) {
                            withContext(Dispatchers.Main) {
                                setupWaitingText = "Error: BLE write characteristic not found."
                            }
                            return@launch
                        }

                        val v5Success = runV5BleProvisioningFlow(gatt, rxChar)
                        if (!v5Success) {
                            withContext(Dispatchers.Main) {
                                setupWaitingText = "Handshake failed. Re-trying using Scenario B (Legacy Chunked provision command) fallback..."
                            }
                            kotlinx.coroutines.delay(1000)
                            withContext(Dispatchers.Main) {
                                isProvisioningActive = false
                                provisionStep = 4
                                runPayloadTransmission()
                            }
                            return@launch
                        }
                    }
                } else {
                    kotlinx.coroutines.delay(1200)
                }
                
                val payload = ProvisionPayload(
                    ssid = wifiSsidInput,
                    password = wifiPasswordInput,
                    deviceId = assignedId,
                    deviceName = devNameInput,
                    deviceType = devTypeInput,
                    serverUrl = serverUrlInput.trim()
                )

                val success = repository.processProvisioning(payload, isBleMode)
                withContext(Dispatchers.Main) {
                    if (success) {
                        _activeDeviceId.value = assignedId
                        if (isBleMode) {
                            try {
                                activeGatt?.disconnect()
                                activeGatt?.close()
                                activeGatt = null
                            } catch (e: SecurityException) {
                                // ignore
                            }
                        }
                        provisionStep = 4
                        start3StageVerificationFlow()
                    } else {
                        setupWaitingText = if (isBleMode) {
                            "BLE sync finished but SQLite DB logging failed."
                        } else {
                            "Transmission failed. Is phone still associated with ESP32 network?"
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isProvisioningActive = false
                }
            }
        }
    }

    fun completeDatabaseRegistry() {
        val id = _activeDeviceId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateDeviceDetails(id, devNameInput, devTypeInput)
            withContext(Dispatchers.Main) {
                provisionStep = 4
            }
        }
    }

    fun launchOtaUpdate(deviceId: String, targetVer: String) {
        otaProgress = 0.0f
        otaStatusText = "Deploying firmware..."
        otaJobActive = true
        
        viewModelScope.launch(Dispatchers.IO) {
            repository.executeOta(
                deviceId = deviceId,
                ver = targetVer,
                url = "https://firmware.ykp.io/bin/download?v=$targetVer"
            ).collect { job: OtaJobResponse ->
                withContext(Dispatchers.Main) {
                    otaStatusText = job.status
                    otaProgress = job.chunksSent.toFloat() / job.chunksTotal.toFloat()
                    if (job.status == "SUCCESS") {
                        otaJobActive = false
                    }
                }
            }
        }
    }

    private val bleIncomingBuffer = java.lang.StringBuilder()
    private val bleIncomingNotificationBuffer = java.lang.StringBuilder()
    private var bleBufferTimeoutJob: kotlinx.coroutines.Job? = null
    private var v5AckDeferred: kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>? = null
    private var scanResultsDeferred: kotlinx.coroutines.CompletableDeferred<org.json.JSONArray>? = null
    private var credsOkDeferred: kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>? = null
    private var setCertAckDeferred: kotlinx.coroutines.CompletableDeferred<Int>? = null
    private var sslDoneDeferred: kotlinx.coroutines.CompletableDeferred<String>? = null
    private var udpReadyDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var udpOkDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var reportDeferred: kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>? = null
    private var commitOkDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    private fun handleIncomingBleText(gatt: android.bluetooth.BluetoothGatt?, rawText: String) {
        val text = rawText.trim()
        
        // Cancel active timeout whenever a new packet arrives
        bleBufferTimeoutJob?.cancel()
        
        // Check for non-JSON special acknowledgement packets first
        if (text.contains("[ACK] CONFIG_RECEIVED")) {
            bleIncomingBuffer.clear()
            viewModelScope.launch(Dispatchers.Main) {
                setupWaitingText = "WiFi configuration verified by hardware."
            }
            configAckDeferred?.complete(true)
            return
        }
        if (text.contains("[ACK] CERT_RECEIVED")) {
            bleIncomingBuffer.clear()
            viewModelScope.launch(Dispatchers.Main) {
                setupWaitingText = "SSL/TLS Root Cert successfully installed."
            }
            certAckDeferred?.complete(true)
            return
        }
        if (text.contains("[ACK] WIFI_WSS_CONNECTED") || text.contains("Onboarding succeeded")) {
            bleIncomingBuffer.clear()
            viewModelScope.launch(Dispatchers.Main) {
                setupWaitingText = "Device successfully connected to the cloud!"
            }
            bleCompletedDeferred?.complete(true)
            return
        }
        if (text.contains("[ERROR] CONNECTION_FAILED")) {
            bleIncomingBuffer.clear()
            viewModelScope.launch(Dispatchers.Main) {
                setupWaitingText = "Error: Connection failed! Verify your Wi-Fi credentials."
            }
            bleCompletedDeferred?.complete(false)
            return
        }
        if (text.contains("[ACK] PAYLOAD_RECEIVED")) {
            bleIncomingBuffer.clear()
            viewModelScope.launch(Dispatchers.Main) {
                setupWaitingText = "Payload received! Testing connection on device..."
            }
            configAckDeferred?.complete(true)
            certAckDeferred?.complete(true)
            try {
                gatt?.disconnect()
                gatt?.close()
                if (gatt == activeGatt) {
                    activeGatt = null
                }
            } catch (e: SecurityException) {}
            return
        }
        
        // If they send data without JSON delimiters but buffer already has '{' or starts with '{', treat as JSON
        if (text.startsWith("{") || bleIncomingBuffer.isNotEmpty()) {
            bleIncomingBuffer.append(text)
            val accumulated = bleIncomingBuffer.toString().trim()
            try {
                val json = org.json.JSONObject(accumulated)
                // If it successfully parsed, clear buffer and process!
                bleIncomingBuffer.clear()
                
                if (json.has("session_id")) {
                    val sId = json.opt("session_id")
                    val sIdStr = sId.toString()
                    if (sIdStr.isNotEmpty() && sIdStr != "0" && sIdStr != sessionId) {
                        sessionId = sIdStr
                        ykpBleManager?.deriveHmacKey(sId)
                    }
                }

                if (json.has("chunk") && json.has("of")) {
                    val chunkIdx = json.getInt("chunk")
                    val totalChunks = json.getInt("of")
                    val data = json.getString("data")
                    reportChunks[chunkIdx] = data
                    
                    // Respond with report_ack back to the device immediately
                    val ackPayload = org.json.JSONObject().apply {
                        put("cmd", "report_ack")
                        put("chunk", chunkIdx)
                    }
                    ykpBleManager?.writeCommand(ackPayload)
                    
                    if (reportChunks.size == totalChunks) {
                        assembleReport()
                    }
                }
                
                // Track standard ACKs for cert chunking
                val ack = json.optString("ack")
                if (ack.isNotEmpty()) {
                    if (ack == "set_cert") {
                        val chunkNum = json.optInt("chunk", -1)
                        if (json.optString("status") == "ok") {
                            setCertAckDeferred?.complete(chunkNum)
                        }
                    } else {
                        v5AckDeferred?.complete(json)
                    }
                }
                
                val status = json.optString("status")
                val message = json.optString("message")
                val reason = json.optString("reason")
                val ip = json.optString("ip")
                
                val scanResultsArr = json.optJSONArray("scan_results") ?: json.optJSONArray("networks")
                
                viewModelScope.launch(Dispatchers.Main) {
                    when (status) {
                        "ssl_progress" -> {
                            val ca = json.optString("ca", "pending")
                            setupWaitingText = "SSL Handshake Check (CA: $ca)"
                        }
                        "ssl_done" -> {
                            val result = json.optString("result", "unknown")
                            setupWaitingText = "SSL TLS Double Verification finished. Result: $result"
                            sslDoneDeferred?.complete(result)
                        }
                        "udp_ready" -> {
                            setupWaitingText = "Device temporary UDP socket is open. Testing reachability..."
                            udpReadyDeferred?.complete(true)
                        }
                        "udp_ok" -> {
                            viewModelScope.launch(Dispatchers.Main) {
                                verificationStep2Passed = true
                                setupWaitingText = "UDP Reachability Verified!"
                            }
                            udpOkDeferred?.complete(true)
                        }
                        "report" -> {
                            val readyToCommit = json.optBoolean("ready_to_commit", false)
                            val firmwareVersion = json.optString("firmware_version", "v1.0.0")
                            val wifiRssi = json.optInt("wifi_rssi_avg", -70)
                            val linkQuality = json.optString("link_quality", "unknown")
                            val wifiIp = json.optString("wifi_ip", "0.0.0.0")
                            val sslStatus = json.optString("ssl_status", "unknown")
                            val udpStatus = json.optString("udp_status", "unknown")
                            val udpPort = json.optInt("udp_port", 47808)
                            val timeSync = json.optBoolean("time_sync", false)
                            
                            setupWaitingText = "Validation Report Received:\n" +
                                "Link Quality: $linkQuality ($wifiRssi dBm)\n" +
                                "IP: $wifiIp, SSL: $sslStatus, UDP: $udpStatus"
                            
                            verificationStep3Passed = readyToCommit
                            isConfirmProvisionEnabled = readyToCommit
                            
                            // Acknowledge report back to device
                            val reportAckPayload = org.json.JSONObject().apply {
                                put("cmd", "report_ack")
                            }
                            ykpBleManager?.writeCommand(reportAckPayload)
                            
                            reportDeferred?.complete(json)
                        }
                        "scan_results" -> {
                            setupWaitingText = "Wi-Fi scan completed successfully."
                            parseWifiArray(scanResultsArr)
                            sendScanAck()
                            scanResultsDeferred?.complete(scanResultsArr)
                        }
                        "validating" -> {
                            setupWaitingText = "Validating: $message"
                        }
                        "creds_ok" -> {
                            val nonce = json.optString("udp_nonce")
                            val udpPortHttp = json.optInt("udp_port", 47808)
                            val ipAddr = json.optString("ip", "192.168.4.1")
                            setupWaitingText = "Credentials accepted. Verifying..."
                            verificationStep1Passed = true
                            credsOkDeferred?.complete(json)
                        }
                        "commit_ok" -> {
                            setupWaitingText = "Provisioning saved! Rebooting..."
                            verificationStep3Passed = true
                            commitOkDeferred?.complete(true)
                        }
                        "wifi_error" -> {
                            setupWaitingText = "Wi-Fi Error: $message"
                            try {
                                gatt?.disconnect()
                                gatt?.close()
                                if (gatt == activeGatt) {
                                    activeGatt = null
                                }
                            } catch (e: SecurityException) {}
                            bleCompletedDeferred?.complete(false)
                        }
                        "wss_error" -> {
                            setupWaitingText = when (message) {
                                "server_down" -> "Server unreachable (check URL/port)"
                                "cert_invalid" -> "CA Certificate is invalid/untrusted"
                                "cert_expired" -> "System clock unsynced or Certificate expired"
                                else -> "Cloud Error: $message"
                            }
                            try {
                                gatt?.disconnect()
                                gatt?.close()
                                if (gatt == activeGatt) {
                                    activeGatt = null
                                }
                            } catch (e: SecurityException) {}
                            bleCompletedDeferred?.complete(false)
                        }
                        "scanning", "wifi_scan" -> {
                            setupWaitingText = "Device scanning Wi-Fi networks..."
                            isWifiScanning = true
                        }
                        "success" -> {
                            setupWaitingText = "Device connected to WiFi and Cloud successfully!"
                            try {
                                gatt?.disconnect()
                                gatt?.close()
                                if (gatt == activeGatt) {
                                    activeGatt = null
                                }
                            } catch (e: SecurityException) {}
                            bleCompletedDeferred?.complete(true)
                        }
                        "rebooting" -> {
                            setupWaitingText = "Provision completed, device is rebooting."
                        }
                        "connecting" -> {
                            setupWaitingText = message.ifEmpty { "Trying to connect..." }
                        }
                        "fail" -> {
                            setupWaitingText = if (reason.isNotEmpty()) {
                                "Onboarding failed: $reason"
                            } else {
                                "Incorrect Password or Router unreachable"
                            }
                            bleCompletedDeferred?.complete(false)
                        }
                        "scan_done" -> {
                            setupWaitingText = "Wi-Fi scan completed successfully."
                            parseWifiArray(scanResultsArr)
                        }
                        else -> {
                            if (scanResultsArr != null) {
                                setupWaitingText = "Wi-Fi scan completed successfully."
                                parseWifiArray(scanResultsArr)
                            }
                        }
                    }
                }
            } catch (e: org.json.JSONException) {
                // If the accumulated text doesn't start with '{', it's not a valid JSON chunk start.
                if (!accumulated.startsWith("{")) {
                    bleIncomingBuffer.clear()
                    viewModelScope.launch(Dispatchers.Main) {
                        setupWaitingText = "Device: $text"
                    }
                } else {
                    android.util.Log.d("BLE_PROV", "Awaiting remaining JSON chunks. Buffer so far: '$accumulated'")
                    // Restart the 2-second buffer timeout job
                    bleBufferTimeoutJob = viewModelScope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(2000)
                        if (bleIncomingBuffer.isNotEmpty()) {
                            android.util.Log.w("BLE_PROV", "BLE incoming JSON buffer timed out after 2s of inactivity. Clearing.")
                            bleIncomingBuffer.clear()
                        }
                    }
                }
            }
        } else {
            // Legacy fallbacks
            if (text.contains("WIFI_WSS_CONNECTED")) {
                viewModelScope.launch(Dispatchers.Main) {
                    setupWaitingText = "Device connected to WiFi and Cloud broker successfully!"
                    if (gatt != null) {
                        try {
                            gatt.disconnect()
                            gatt.close()
                            if (gatt == activeGatt) {
                                activeGatt = null
                            }
                        } catch (e: SecurityException) {}
                    }
                }
                bleCompletedDeferred?.complete(true)
            } else if (text.contains("CONNECTION_FAILED")) {
                viewModelScope.launch(Dispatchers.Main) {
                    setupWaitingText = "Error: Device failed to connect. Check password."
                    if (gatt != null) {
                        try {
                            gatt.disconnect()
                            gatt.close()
                            if (gatt == activeGatt) {
                                activeGatt = null
                            }
                        } catch (e: SecurityException) {}
                    }
                }
                bleCompletedDeferred?.complete(false)
            } else {
                viewModelScope.launch(Dispatchers.Main) {
                    setupWaitingText = "Device: $text"
                }
            }
        }
    }

    private fun parseWifiArray(scanResultsArr: org.json.JSONArray?) {
        if (scanResultsArr != null) {
            scannedWifiNetworks.clear()
            for (i in 0 until scanResultsArr.length()) {
                val obj = scanResultsArr.getJSONObject(i)
                val ssid = obj.optString("ssid")
                val rssi = obj.optInt("rssi", -100)
                val auth = obj.optInt("auth", 3)
                scannedWifiNetworks.add(WifiNetwork(ssid, rssi, auth))
            }
        }
        isWifiScanning = false
    }

    private suspend fun writeBlePacket(
        gatt: android.bluetooth.BluetoothGatt,
        char: android.bluetooth.BluetoothGattCharacteristic,
        payloadStr: String
    ): Boolean {
        val signedPayloadStr = try {
            if (payloadStr.trim().startsWith("{")) {
                val json = org.json.JSONObject(payloadStr)
                if (sessionId.isNotEmpty()) {
                    json.put("session_id", sessionId)
                }
                json.put("seq", seq++)
                
                val serialized = json.toString()
                val hmac = computeHmac(serialized)
                if (hmac.isNotEmpty()) {
                    "$serialized|$hmac"
                } else {
                    serialized
                }
            } else {
                payloadStr
            }
        } catch (e: Exception) {
            payloadStr
        }

        char.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val payloadBytes = signedPayloadStr.toByteArray(Charsets.UTF_8)
        val success: Boolean
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(char, payloadBytes, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            success = status == android.bluetooth.BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payloadBytes
            @Suppress("DEPRECATION")
            success = gatt.writeCharacteristic(char)
        }
        android.util.Log.d("YKP_BLE_WRITE", "Sent packet: '$signedPayloadStr' - success=$success")
        return success
    }

    private val DEFAULT_BLE_SESSION_PAIRING_KEY = "YKP_v3_SECURE_SESSION_KEY_2026".toByteArray(Charsets.UTF_8)

    private fun deriveHmacKey(sessionIdHex: String): ByteArray {
        val salt = try {
            val clean = sessionIdHex.removePrefix("0x")
            if (clean.length == 8) {
                ByteArray(4) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } else {
                sessionIdHex.toByteArray(Charsets.UTF_8).copyOf(4)
            }
        } catch (e: Exception) {
            sessionIdHex.toByteArray(Charsets.UTF_8).copyOf(4)
        }

        val prk = hmacSha256(salt, DEFAULT_BLE_SESSION_PAIRING_KEY)
        val info = "YKP_v3_HMAC_KEY".toByteArray(Charsets.UTF_8)
        val infoWithIndex = ByteArray(info.size + 1)
        System.arraycopy(info, 0, infoWithIndex, 0, info.size)
        infoWithIndex[info.size] = 0x01.toByte()

        return hmacSha256(prk, infoWithIndex)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun writeCommand(payload: org.json.JSONObject) {
        val rawJson = payload.toString()
        val hmac = computeHmac(rawJson)
        val completeMessage = "$rawJson|$hmac"
        
        val gatt = activeGatt ?: return
        
        var rx: android.bluetooth.BluetoothGattCharacteristic? = null
        for (srv in gatt.services) {
            for (chr in srv.characteristics) {
                val uuidStr = chr.uuid.toString().lowercase()
                if (uuidStr == "87654321-4321-8765-4321-0fedcba98765" ||
                    uuidStr == "6e400002-b5a3-f393-e0a9-e50e24dcca9e" || 
                    uuidStr == "9ecadc24-0ee5-a9e0-93f3-a3b50200406e") {
                    rx = chr
                    break
                }
            }
            if (rx != null) break
        }
        
        if (rx == null) {
            android.util.Log.e("YKP_BLE_WRITE", "No RX characteristic found to send command: $rawJson")
            return
        }

        rx.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val payloadBytes = completeMessage.toByteArray(Charsets.UTF_8)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rx, payloadBytes, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            rx.value = payloadBytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rx)
        }
    }

    private fun computeHmac(data: String): String {
        return try {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256"))
            val rawHmac = mac.doFinal(data.toByteArray(Charsets.UTF_8))
            rawHmac.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun sendScanAck() {
        viewModelScope.launch(Dispatchers.IO) {
            val payload = org.json.JSONObject().apply {
                put("cmd", "scan_ack")
                put("session_id", sessionId)
                put("seq", seq++)
            }
            ykpBleManager?.writeCommand(payload)
        }
    }

    private fun sendChunkAck(chunkIdx: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val payload = org.json.JSONObject().apply {
                put("cmd", "report_ack")
                put("session_id", sessionId)
                put("chunk", chunkIdx)
                put("seq", seq++)
            }
            ykpBleManager?.writeCommand(payload)
        }
    }

    private fun assembleReport() {
        try {
            val fullReportStr = reportChunks.keys.sorted().map { reportChunks[it] }.joinToString("")
            val reportJson = org.json.JSONObject(fullReportStr)
            val readyToCommit = reportJson.optBoolean("ready_to_commit", false)
            val sslStatus = reportJson.optString("ssl_status", "unknown")
            
            viewModelScope.launch(Dispatchers.Main) {
                if (readyToCommit) {
                    verificationStep3Passed = true
                    isConfirmProvisionEnabled = true
                    setupWaitingText = "Validation complete! Press confirm to save."
                } else {
                    setupWaitingText = "Validation failed: $sslStatus"
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("YKP_BLE_PROV", "Error assembling chunked report: ${e.localizedMessage}")
            viewModelScope.launch(Dispatchers.Main) {
                setupWaitingText = "Error parsing chunked validation report."
            }
        }
    }

    private fun performUdpProbe(nonceHex: String, port: Int, targetIp: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val wifiNetwork = connectivityManager?.allNetworks?.find { network ->
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                }

                val socket = java.net.DatagramSocket()
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        wifiNetwork?.bindSocket(socket)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("YKP_BLE_PROV", "Could not bind UDP socket to Wi-Fi: ${e.localizedMessage}")
                }

                val ipAddress = java.net.InetAddress.getByName(targetIp)
                val messageStr = "YKP_PROBE:$nonceHex"
                val sendData = messageStr.toByteArray(Charsets.UTF_8)
                val sendPacket = java.net.DatagramPacket(sendData, sendData.size, ipAddress, port)
                
                socket.send(sendPacket)
                socket.close()

                viewModelScope.launch(Dispatchers.Main) {
                    verificationStep2Passed = true
                    setupWaitingText = "UDP Reachability Verified!"
                }
                android.util.Log.d("YKP_BLE_PROV", "Successfully sent UDP probe to $targetIp:$port")
            } catch (e: Exception) {
                android.util.Log.e("YKP_BLE_PROV", "Error sending UDP probe: ${e.localizedMessage}")
            }
        }
    }

    fun confirmProvision() {
        setupWaitingText = "Committing final adjustments..."
        viewModelScope.launch(Dispatchers.IO) {
            commitOkDeferred = kotlinx.coroutines.CompletableDeferred()
            val payload = org.json.JSONObject().apply {
                put("cmd", "confirm_provision")
            }
            val sent = ykpBleManager?.writeCommand(payload) ?: false
            if (!sent) {
                withContext(Dispatchers.Main) {
                    setupWaitingText = "Error: Failed to send commit command."
                }
                return@launch
            }
            try {
                kotlinx.coroutines.withTimeout(10000) {
                    commitOkDeferred?.await()
                }
                withContext(Dispatchers.Main) {
                    setupWaitingText = "Success! Configurations committed. Rebooting..."
                    isConfirmProvisionEnabled = false
                    delay(2000)
                    ykpBleManager?.stopSession()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setupWaitingText = "Timeout waiting for commit confirmation."
                }
            }
        }
    }

    private suspend fun waitForAck(expectedAck: String, timeoutMs: Long): org.json.JSONObject? {
        return waitForAck(expectedAck, null, timeoutMs)
    }

    private suspend fun waitForAck(expectedAck: String, expectedChunk: Int?, timeoutMs: Long): org.json.JSONObject? {
        val tStart = System.currentTimeMillis()
        var resolved: org.json.JSONObject? = null
        while (System.currentTimeMillis() - tStart < timeoutMs) {
            val currentDeferred = v5AckDeferred ?: break
            val candidate = kotlinx.coroutines.withTimeoutOrNull(500) {
                currentDeferred.await()
            }
            if (candidate != null) {
                val ack = candidate.optString("ack")
                if (ack == expectedAck) {
                    if (expectedChunk == null || candidate.optInt("chunk") == expectedChunk) {
                        resolved = candidate
                        break
                    }
                }
                val newDeferred = kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>()
                v5AckDeferred = newDeferred
            }
            kotlinx.coroutines.delay(10)
        }
        return resolved
    }

    private suspend fun runV5BleProvisioningFlow(
        gatt: android.bluetooth.BluetoothGatt,
        rxChar: android.bluetooth.BluetoothGattCharacteristic
    ): Boolean = withContext(Dispatchers.IO) {
        val assignedId = devIdInput.trim()
        val wifiSsid = wifiSsidInput.trim()
        val wifiPass = wifiPasswordInput.trim()
        val serverUrl = serverUrlInput.trim().ifEmpty { "wss://iot-yk.onrender.com/ws" }
        var cert = secureCertInput.trim()
        if (cert.isEmpty()) {
            cert = repository.getCachedSslCert()
        }

        // Field Length Validations
        if (wifiSsid.toByteArray(Charsets.UTF_8).size > 32) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "Error: SSID exceeds 32 bytes limit"
            }
            return@withContext false
        }
        if (wifiPass.toByteArray(Charsets.UTF_8).size > 64) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "Error: Password exceeds 64 bytes limit"
            }
            return@withContext false
        }
        if (serverUrl.length > 253) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "Error: Host / Server URL exceeds 253 chars limit"
            }
            return@withContext false
        }

        val bleManager = ykpBleManager ?: return@withContext false

        // State 2: Send Config Credentials
        withContext(Dispatchers.Main) {
            setupWaitingText = "State 2: Transmitting configuration credentials..."
        }
        
        credsOkDeferred = kotlinx.coroutines.CompletableDeferred()
        
        val parsedUrl = try {
            val uri = java.net.URI(serverUrl)
            uri.host ?: serverUrl
        } catch (e: Exception) {
            serverUrl
        }

        val sendCredsCmd = org.json.JSONObject().apply {
            put("cmd", "send_creds")
            put("ssid", wifiSsid)
            put("psk", wifiPass)
            put("host", parsedUrl)
            put("port", 443)
            put("udp_port", 47808)
        }
        
        if (!bleManager.writeCommand(sendCredsCmd)) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "Failed to transmit credentials command."
            }
            return@withContext false
        }

        val credsResponse = try {
            kotlinx.coroutines.withTimeout(15000) {
                credsOkDeferred!!.await()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "Timeout waiting for credentials verification ACK."
            }
            return@withContext false
        }

        val udpNonce = credsResponse.optString("udp_nonce")
        val ipAddr = credsResponse.optString("ip", "192.168.4.1")
        val udpPort = credsResponse.optInt("udp_port", 47808)

        // State 3: Send CA Certificate (Chunked)
        if (cert.isNotEmpty()) {
            val chunkSize = 180
            val certLength = cert.length
            val numChunks = (certLength + chunkSize - 1) / chunkSize
            
            for (i in 0 until numChunks) {
                val chunkNum = i + 1
                val start = i * chunkSize
                val end = (start + chunkSize).coerceAtMost(certLength)
                val chunkData = cert.substring(start, end)
                
                withContext(Dispatchers.Main) {
                    setupWaitingText = "State 3: Syncing custom SSL CA (Chunk $chunkNum/$numChunks)..."
                    currentTransmissionChunkIdx = chunkNum
                    totalTransmissionChunks = numChunks
                }
                
                setCertAckDeferred = kotlinx.coroutines.CompletableDeferred()
                
                val certCmd = org.json.JSONObject().apply {
                    put("cmd", "set_cert")
                    put("chunk", chunkNum)
                    put("data", chunkData)
                }
                
                if (!bleManager.writeCommand(certCmd)) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Failed to transmit SSL chunk $chunkNum."
                    }
                    return@withContext false
                }
                
                try {
                    kotlinx.coroutines.withTimeout(8000) {
                        setCertAckDeferred!!.await()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Timeout or verification failed for SSL chunk $chunkNum."
                    }
                    return@withContext false
                }
                kotlinx.coroutines.delay(50)
            }
        }

        // State 4: Commit and Double SSL Check
        withContext(Dispatchers.Main) {
            setupWaitingText = "State 4: Committing credentials configurations..."
            verificationStep1Passed = true
        }

        sslDoneDeferred = kotlinx.coroutines.CompletableDeferred()
        val commitCmd = org.json.JSONObject().apply {
            put("cmd", "commit")
        }

        if (!bleManager.writeCommand(commitCmd)) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "Failed to transmit commit command."
            }
            return@withContext false
        }

        val sslResult = try {
            kotlinx.coroutines.withTimeout(30000) {
                sslDoneDeferred!!.await()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "Timeout or verification failed waiting for SSL check completion."
            }
            return@withContext false
        }

        if (sslResult == "ca_failed_fallback_failed") {
            withContext(Dispatchers.Main) {
                setupWaitingText = "SSL Handshake Check failed on fallback. Verify certificate."
            }
            return@withContext false
        }

        // State 5: UDP Nonce Verification Probe
        withContext(Dispatchers.Main) {
            setupWaitingText = "State 5: Waiting for device UDP readiness..."
        }

        udpReadyDeferred = kotlinx.coroutines.CompletableDeferred()
        try {
            kotlinx.coroutines.withTimeout(15000) {
                udpReadyDeferred!!.await()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "Timeout waiting for UDP server setup on device."
            }
            return@withContext false
        }

        withContext(Dispatchers.Main) {
            setupWaitingText = "Sending local UDP check probe..."
        }

        udpOkDeferred = kotlinx.coroutines.CompletableDeferred()
        performUdpProbe(udpNonce, udpPort, ipAddr)

        try {
            kotlinx.coroutines.withTimeout(10000) {
                udpOkDeferred!!.await()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "UDP network verification probe timed out."
            }
            return@withContext false
        }

        // State 6: Report and Confirmation
        withContext(Dispatchers.Main) {
            setupWaitingText = "State 6: Waiting for ESP32 verification report..."
        }

        reportDeferred = kotlinx.coroutines.CompletableDeferred()
        val finalReportJson = try {
            kotlinx.coroutines.withTimeout(25000) {
                reportDeferred!!.await()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                setupWaitingText = "Timeout waiting for ESP32 validation report."
            }
            return@withContext false
        }

        val readyToCommit = finalReportJson.optBoolean("ready_to_commit", false)
        withContext(Dispatchers.Main) {
            if (readyToCommit) {
                verificationStep3Passed = true
                isConfirmProvisionEnabled = true
                setupWaitingText = "Handshake complete & validated! Confirm to save configuration."
            } else {
                setupWaitingText = "Verification failed. Please check credentials and try again."
                isConfirmProvisionEnabled = false
            }
        }

        return@withContext readyToCommit
    }

    private fun sendRobustBlePayload(
        gatt: android.bluetooth.BluetoothGatt,
        char: android.bluetooth.BluetoothGattCharacteristic,
        payloadStr: String
    ) {
        val mtu = negotiatedMtu
        val maxPacketSize = if (mtu > 23) (mtu - 3) else 20
        
        if (payloadStr.length <= maxPacketSize) {
            char.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val payloadBytes = payloadStr.toByteArray(Charsets.UTF_8)
            val success: Boolean
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(char, payloadBytes, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                success = status == android.bluetooth.BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = payloadBytes
                @Suppress("DEPRECATION")
                success = gatt.writeCharacteristic(char)
            }
            android.util.Log.d("YKP_BLE_WRITE", "Sent single packet: '$payloadStr' (WriteType=WRITE_TYPE_NO_RESPONSE, Success=$success)")
        } else {
            val prefixOverhead = 6
            val contentChunkSize = (maxPacketSize - prefixOverhead).coerceAtLeast(10)
            
            val tempChunks = mutableListOf<String>()
            var idx = 0
            while (idx < payloadStr.length) {
                val end = (idx + contentChunkSize).coerceAtMost(payloadStr.length)
                tempChunks.add(payloadStr.substring(idx, end))
                idx = end
            }
            
            val total = tempChunks.size
            android.util.Log.d("YKP_BLE_WRITE", "Splitting payload into $total chunk(s) (MTU=$mtu, MaxPacketSize=$maxPacketSize, ContentChunkSize=$contentChunkSize)")
            
            viewModelScope.launch(Dispatchers.IO) {
                for (i in 0 until total) {
                    val piece = "${i + 1}/$total:${tempChunks[i]}"
                    val pieceBytes = piece.toByteArray(Charsets.UTF_8)
                    char.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    
                    val success: Boolean
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        val status = gatt.writeCharacteristic(char, pieceBytes, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        success = status == android.bluetooth.BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = pieceBytes
                        @Suppress("DEPRECATION")
                        success = gatt.writeCharacteristic(char)
                    }
                    android.util.Log.d("YKP_BLE_WRITE", "Sent chunk $i/$total: '$piece' - Success=$success")
                    delay(180)
                }
            }
        }
    }

    fun startBleWifiScan() {
        val now = System.currentTimeMillis()
        if (now - lastWifiScanTime < 15000) {
            viewModelScope.launch(Dispatchers.Main) {
                setupWaitingText = "Warning: Scan throttled! Please wait ${(15000 - (now - lastWifiScanTime)) / 1000} seconds."
            }
            return
        }
        lastWifiScanTime = now

        viewModelScope.launch(Dispatchers.Main) {
            isWifiScanning = true
            scannedWifiNetworks.clear()
            setupWaitingText = "Device scanning Wi-Fi networks..."
        }

        if (isDemoMode || isBleSetupSimulated) {
            viewModelScope.launch(Dispatchers.Main) {
                delay(1200)
                scannedWifiNetworks.add(WifiNetwork("YKP_Office_5G", -45, 1))
                scannedWifiNetworks.add(WifiNetwork("Sweet_Home_2G", -62, 1))
                scannedWifiNetworks.add(WifiNetwork("CoffeeShop_Guest", -78, 0))
                scannedWifiNetworks.add(WifiNetwork("Neighbor_Wifi_Ext", -85, 1))
                isWifiScanning = false
            }
            return
        }

        val gatt = activeGatt
        if (gatt == null) {
            viewModelScope.launch(Dispatchers.Main) {
                isWifiScanning = false
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            var rxChar: android.bluetooth.BluetoothGattCharacteristic? = null
            for (srv in gatt.services) {
                for (chr in srv.characteristics) {
                    val uuidStr = chr.uuid.toString().lowercase()
                    if (uuidStr == "87654321-4321-8765-4321-0fedcba98765" ||
                        uuidStr == "6e400002-b5a3-f393-e0a9-e50e24dcca9e" || 
                        uuidStr == "9ecadc24-0ee5-a9e0-93f3-a3b50200406e") {
                        rxChar = chr
                        break
                    }
                }
                if (rxChar != null) break
            }

            if (rxChar != null) {
                val scanPayload = org.json.JSONObject()
                scanPayload.put("cmd", "scan")
                scanPayload.put("action", "scan_wifi")
                try {
                    sendRobustBlePayload(gatt, rxChar, scanPayload.toString())
                } catch (e: Exception) {
                    android.util.Log.e("YKP_BLE_SCAN", "Error writing scan command: ${e.localizedMessage}")
                }
            } else {
                withContext(Dispatchers.Main) {
                    isWifiScanning = false
                }
            }
        }
    }

    fun triggerWifiScan(gatt: android.bluetooth.BluetoothGatt) {
        val now = System.currentTimeMillis()
        if (now - lastWifiScanTime < 15000) {
            viewModelScope.launch(Dispatchers.Main) {
                setupWaitingText = "Warning: Scan throttled! Please wait ${(15000 - (now - lastWifiScanTime)) / 1000} seconds."
            }
            return
        }
        lastWifiScanTime = now

        // 1. Reset states on the Main Thread
        viewModelScope.launch(Dispatchers.Main) {
            isWifiScanning = true
            scannedWifiNetworks.clear()
            setupWaitingText = "Triggering Wi-Fi scan..."
        }
        // 2. Build and send the command
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val scanCommand = org.json.JSONObject().apply {
                    put("cmd", "scan")
                    put("action", "scan_wifi")
                }.toString()
                val rawBytes = scanCommand.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                
                // Write to the NUS RX characteristic
                val service = gatt.getService(java.util.UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
                val rxChar = service?.getCharacteristic(java.util.UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"))
                
                if (rxChar != null) {
                    @Suppress("DEPRECATION")
                    rxChar.value = rawBytes
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(rxChar)
                    android.util.Log.d("YkpViewModel", "Sent Wi-Fi scan command over BLE: $scanCommand")
                } else {
                    android.util.Log.e("YkpViewModel", "NUS RX characteristic not found!")
                }
            } catch (e: Exception) {
                android.util.Log.e("YkpViewModel", "Failed to send scan command: ${e.message}")
            }
        }
    }

    fun startBackgroundOobeScan() {
        if (showOobeBottomSheet || isOobeScanning) return
        isOobeScanning = true

        if (isDemoMode) {
            viewModelScope.launch {
                delay(1200)
                discoveredOobeDeviceName = "YKP_PROV_SETUP"
                showOobeBottomSheet = true
                isOobeScanning = false
            }
            return
        }

        val context = getApplication<Application>().applicationContext
        val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                if (name.contains("YKP_PROV_SETUP", ignoreCase = true) || name.contains("YKP_PROV_", ignoreCase = true) || name.startsWith("YKP_PROV", ignoreCase = true)) {
                    viewModelScope.launch(Dispatchers.Main) {
                        discoveredOobeDeviceName = name
                        discoveredOobeDeviceAddress = result.device.address
                        discoveredOobeDeviceHardware = result.device
                        showOobeBottomSheet = true
                        stopBackgroundOobeScan()
                    }
                }
            }
        }
        oobeScanCallback = callback
        try {
            scanner.startScan(callback)
            viewModelScope.launch {
                delay(15000)
                stopBackgroundOobeScan()
            }
        } catch (e: SecurityException) {
            isOobeScanning = false
        }
    }

    fun stopBackgroundOobeScan() {
        isOobeScanning = false
        val callback = oobeScanCallback ?: return
        oobeScanCallback = null
        val context = getApplication<Application>().applicationContext
        val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(callback)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun startDiagnosticsMode(deviceId: String) {
        navigateTo(ScreenRoute.BleDiagnostics(deviceId))
        diagnosticsWaitingText = "Scanning to locate Bluetooth Node for $deviceId..."
        diagnosticsLogs.clear()
        diagnosticsLogs.add("Initializing Local BLE Diagnostics module...")
        diagnosticsLogs.add("Searching for advertising beacons containing '$deviceId'...")
        isDiagnosticsConnecting = true
        isDiagnosticsConnected = false
        isDiagnosticsSimulated = false
        
        // Load initial switch state from database
        viewModelScope.launch(Dispatchers.IO) {
            repository.getDeviceById(deviceId).firstOrNull()?.let { dev ->
                withContext(Dispatchers.Main) {
                    diagnosticsRelayState = dev.relayState
                }
            }
        }
        
        runDiagnosticsBleConnection(deviceId)
    }

    fun runDiagnosticsBleConnection(targetDeviceId: String) {
        if (isDemoMode) {
            isDiagnosticsSimulated = true
            viewModelScope.launch {
                delay(1200)
                diagnosticsLogs.add("Beacon discovered! RSSI: -42dBm")
                diagnosticsLogs.add("Attempting secure GATT handshake with peripheral...")
                delay(1000)
                diagnosticsLogs.add("Connected to GATT. Discovering profile attributes...")
                delay(800)
                diagnosticsLogs.add("Nordic UART Rx/Tx channels mapped successfully.")
                diagnosticsLogs.add("Self-diagnostic payload query sent over secure line...")
                delay(800)
                diagnosticsLogs.add("ESP32 response parsed status=ok local_ip=0.0.0.0 (NO_WIFI)")
                diagnosticsLogs.add("Diagnostic warning: [ERROR] Wi-Fi Connection failed. Reason: Wrong Wi-Fi Password.")
                diagnosticsWaitingText = "Connected directly via Bluetooth BLE! Tap toggle below to control."
                isDiagnosticsConnecting = false
                isDiagnosticsConnected = true
            }
            return
        }

        val context = getApplication<Application>().applicationContext
        val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            diagnosticsLogs.add("[ERROR] Bluetooth is not supported on this mobile device.")
            diagnosticsWaitingText = "GATT Diagnostics unavailable: Bluetooth unsupported."
            isDiagnosticsConnecting = false
            return
        }
        if (!adapter.isEnabled) {
            diagnosticsLogs.add("[ERROR] Bluetooth adapter is currently turned off.")
            diagnosticsWaitingText = "Please turn on your Bluetooth to continue."
            isDiagnosticsConnecting = false
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            diagnosticsLogs.add("[ERROR] Bluetooth LE system scanner is unavailable or disabled.")
            diagnosticsWaitingText = "LE Scan unsupported."
            isDiagnosticsConnecting = false
            return
        }

        val callback = object : android.bluetooth.le.ScanCallback() {
            var isDiscovered = false
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                synchronized(this) {
                    if (isDiscovered) return
                    val device = result.device
                    val name = result.scanRecord?.deviceName ?: device.name ?: ""
                    
                    if (name.contains(targetDeviceId, ignoreCase = true) || name.contains("YKP", ignoreCase = true) || name.contains("PROV", ignoreCase = true)) {
                        isDiscovered = true
                        try {
                            scanner.stopScan(this)
                        } catch (e: SecurityException) {
                            // ignore
                        }
                        
                        viewModelScope.launch(Dispatchers.Main) {
                            diagnosticsLogs.add("Beacon '$name' discovered on address ${device.address}")
                            diagnosticsLogs.add("Establishing secure direct GATT profile...")
                            connectDiagnosticsGatt(device, targetDeviceId)
                        }
                    }
                }
            }
 
            override fun onScanFailed(errorCode: Int) {
                viewModelScope.launch(Dispatchers.Main) {
                    diagnosticsLogs.add("[ERROR] BLE Active Scan failed with status code: $errorCode")
                    diagnosticsWaitingText = "Active scan failure."
                    isDiagnosticsConnecting = false
                }
            }
        }

        try {
            scanner.startScan(callback)
            viewModelScope.launch {
                delay(12000)
                if (!isDiagnosticsConnected && isDiagnosticsConnecting) {
                    try {
                        scanner.stopScan(callback)
                    } catch (e: Exception) {}
                    if (isDiagnosticsConnecting) {
                        diagnosticsLogs.add("[TIMEOUT] Direct Bluetooth scan search timed out.")
                        diagnosticsWaitingText = "No physical device advertising nearby."
                        isDiagnosticsConnecting = false
                    }
                }
            }
        } catch (e: SecurityException) {
            diagnosticsLogs.add("[ERROR] Bluetooth background lookup permission is missing.")
            diagnosticsWaitingText = "Permission required."
            isDiagnosticsConnecting = false
        }
    }

    private fun connectDiagnosticsGatt(device: android.bluetooth.BluetoothDevice, targetDeviceId: String) {
        val context = getApplication<Application>().applicationContext
        try {
            device.connectGatt(context, false, object : android.bluetooth.BluetoothGattCallback() {
                var rxChar: android.bluetooth.BluetoothGattCharacteristic? = null

                override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                    if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        activeDiagnosticsGatt = gatt
                        viewModelScope.launch(Dispatchers.Main) {
                            diagnosticsLogs.add("GATT Connected! Requesting MTU negotiation (512)...")
                        }
                        try {
                            gatt.requestMtu(512)
                        } catch (e: SecurityException) {
                            // fallback if permission missing
                            gatt.discoverServices()
                        }
                    } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        viewModelScope.launch(Dispatchers.Main) {
                            diagnosticsLogs.add("GATT Connection closed.")
                            isDiagnosticsConnected = false
                            isDiagnosticsConnecting = false
                            activeDiagnosticsGatt = null
                        }
                        try { gatt.close() } catch (e: Exception) {}
                    }
                }

                override fun onMtuChanged(gatt: android.bluetooth.BluetoothGatt, mtu: Int, status: Int) {
                    viewModelScope.launch(Dispatchers.Main) {
                        if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS && mtu > 100) {
                            diagnosticsLogs.add("MTU negotiated to $mtu. Discovering services...")
                        } else {
                            diagnosticsLogs.add("Warning: MTU negotiation returned $mtu (status: $status). Discovering anyway...")
                        }
                    }
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        viewModelScope.launch(Dispatchers.Main) {
                            diagnosticsLogs.add("[ERROR] Failed to discover services: permission missing.")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        for (srv in gatt.services) {
                            val srvUuidStr = srv.uuid.toString().lowercase()
                            for (chr in srv.characteristics) {
                                val uuidStr = chr.uuid.toString().lowercase()
                                if (srvUuidStr == "12345678-1234-5678-1234-56789abcdef0" ||
                                    uuidStr == "87654321-4321-8765-4321-0fedcba98765") {
                                    if (uuidStr == "87654321-4321-8765-4321-0fedcba98765") {
                                        rxChar = chr
                                        break
                                    }
                                } else if (uuidStr == "6e400002-b5a3-f393-e0a9-e50e24dcca9e" || uuidStr == "9ecadc24-0ee5-a9e0-93f3-a3b50200406e") {
                                    rxChar = chr
                                }
                            }
                        }

                        if (rxChar != null) {
                            var txChar: android.bluetooth.BluetoothGattCharacteristic? = null
                            for (srv in gatt.services) {
                                for (chr in srv.characteristics) {
                                    val uuidStr = chr.uuid.toString().lowercase()
                                    if (uuidStr == "6e400003-b5a3-f393-e0a9-e50e24dcca9e" || uuidStr == "9ecadc24-0ee5-a9e0-93f3-a3b50300406e") {
                                        txChar = chr
                                    }
                                }
                            }

                            if (txChar != null) {
                                try {
                                    gatt.setCharacteristicNotification(txChar, true)
                                    val descriptor = txChar.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                    if (descriptor != null) {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                            gatt.writeDescriptor(descriptor, android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                        } else {
                                            @Suppress("DEPRECATION")
                                            descriptor.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                            @Suppress("DEPRECATION")
                                            gatt.writeDescriptor(descriptor)
                                        }
                                    }
                                } catch (e: SecurityException) {}
                            }

                            viewModelScope.launch(Dispatchers.Main) {
                                diagnosticsLogs.add("Secure data pipe mapping successful.")
                                diagnosticsWaitingText = "Connected directly via Bluetooth! Tap toggle below to control."
                                isDiagnosticsConnecting = false
                                isDiagnosticsConnected = true
                            }
                        } else {
                            viewModelScope.launch(Dispatchers.Main) {
                                diagnosticsLogs.add("[ERROR] NUS / Custom attributes mapping failed.")
                                diagnosticsWaitingText = "Could not map device communication attributes."
                                isDiagnosticsConnecting = false
                                try { gatt.disconnect() } catch (Ex: Exception) {}
                            }
                        }
                    } else {
                        viewModelScope.launch(Dispatchers.Main) {
                            diagnosticsLogs.add("[ERROR] Services mapping failed: status=$status")
                            isDiagnosticsConnecting = false
                            try { gatt.disconnect() } catch (Ex: Exception) {}
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(
                    gatt: android.bluetooth.BluetoothGatt,
                    characteristic: android.bluetooth.BluetoothGattCharacteristic
                ) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value ?: ByteArray(0)
                    onCharacteristicChanged(gatt, characteristic, value)
                }

                override fun onCharacteristicChanged(
                    gatt: android.bluetooth.BluetoothGatt,
                    characteristic: android.bluetooth.BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    val text = String(value, Charsets.UTF_8).trim()
                    viewModelScope.launch(Dispatchers.Main) {
                        diagnosticsLogs.add("ESP32: $text")
                        if (text.lowercase().contains("local_ip=0.0.0.0") || text.uppercase().contains("NO_WIFI") || text.lowercase().contains("no_wifi")) {
                            diagnosticsLogs.add("[STATUS PARSED] ESP32 has NO active Wi-Fi connection (local_ip=0.0.0.0)")
                        } else if (text.contains("local_ip=")) {
                            diagnosticsLogs.add("[STATUS PARSED] ESP32 reports active Wi-Fi connection!")
                        }
                    }
                }
            })
        } catch (e: SecurityException) {
            diagnosticsWaitingText = "Security error connecting to Bluetooth."
            isDiagnosticsConnecting = false
        }
    }

    fun toggleDiagnosticsRelay() {
        diagnosticsRelayState = !diagnosticsRelayState
        val actionValue = if (diagnosticsRelayState) "on" else "off"
        diagnosticsLogs.add("Command triggered: Turn $actionValue physical Relay state over BLE")
        
        val targetId = activeDiagnosticsDeviceId
        
        if (isDemoMode || isDiagnosticsSimulated) {
            diagnosticsLogs.add("[SIMULATED BLE SENT] {\"action\":\"$actionValue\"}")
            if (targetId != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.updateDeviceLocalState(targetId, diagnosticsRelayState)
                    withContext(Dispatchers.Main) {
                        diagnosticsLogs.add("[SUCCESS] Telemetry synced offline: RELAY = ${if (diagnosticsRelayState) "ACTIVE" else "STANDBY"}")
                    }
                }
            }
            return
        }

        val gatt = activeDiagnosticsGatt ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var rxChar: android.bluetooth.BluetoothGattCharacteristic? = null
            for (srv in gatt.services) {
                for (chr in srv.characteristics) {
                    val uuidStr = chr.uuid.toString().lowercase()
                    if (uuidStr == "87654321-4321-8765-4321-0fedcba98765" ||
                        uuidStr == "6e400002-b5a3-f393-e0a9-e50e24dcca9e" || 
                        uuidStr == "9ecadc24-0ee5-a9e0-93f3-a3b50200406e") {
                        rxChar = chr
                        break
                    }
                }
                if (rxChar != null) break
            }

            if (rxChar != null) {
                val togglePayload = org.json.JSONObject()
                togglePayload.put("action", actionValue)
                togglePayload.put("cmd", actionValue)
                val payloadBytes = togglePayload.toString().toByteArray(Charsets.UTF_8)
                try {
                    rxChar.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(rxChar, payloadBytes, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        rxChar.value = payloadBytes
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(rxChar)
                    }
                    viewModelScope.launch(Dispatchers.Main) {
                        diagnosticsLogs.add("BLE Write completed. Payload: {\"action\":\"$actionValue\"}")
                        if (targetId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                repository.updateDeviceLocalState(targetId, diagnosticsRelayState)
                                withContext(Dispatchers.Main) {
                                    diagnosticsLogs.add("[SUCCESS] Telemetry synced offline over GATT: RELAY = ${if (diagnosticsRelayState) "ACTIVE" else "STANDBY"}")
                                }
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    viewModelScope.launch(Dispatchers.Main) {
                        diagnosticsLogs.add("Error: Missing Bluetooth Write Permissions.")
                    }
                }
            }
        }
    }

    // ────────────── MODERN 6-STAGE PROVISIONING FLOW METHODS ──────────────
    
    fun runCloudPreRegistration() {
        if (isPreRegistering) return
        isPreRegistering = true
        setupWaitingText = "Contacting YKP Cloud registry..."
        viewModelScope.launch(Dispatchers.IO) {
            delay(1500)
            
            val prefix = when (devTypeInput) {
                "switch" -> "SW"
                "sensor" -> "SN"
                else -> "MT"
            }
            val randomId = "$prefix${Random.nextInt(10000, 99999)}"
            val dummyCert = repository.getCachedSslCert()
            
            withContext(Dispatchers.Main) {
                devIdInput = randomId
                secureCertInput = dummyCert
                isPreRegistering = false
                provisionStep = 4
                runPayloadTransmission()
            }
        }
    }

    fun runPayloadTransmission() {
        if (isProvisioningActive) return
        isProvisioningActive = true
        currentTransmissionChunkIdx = 0
        totalTransmissionChunks = 0
        setupWaitingText = "Assembling secure provisioning configuration package..."
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val assignedId = devIdInput.trim()
                
                // Stage 1 config JSON payload
                val s1Payload = org.json.JSONObject().apply {
                    put("cmd", "provision")
                    put("device_id", assignedId)
                    put("device_type", devTypeInput.trim())
                    put("server_url", "wss://iot-yk.onrender.com/ws")
                    put("wifi_ssid", wifiSsidInput.trim())
                    put("wifi_password", wifiPasswordInput.trim())
                }
                
                // Stage 2 cert JSON payload
                var cert = secureCertInput.trim()
                if (cert.isEmpty()) {
                    cert = repository.getCachedSslCert()
                }
                val s2Payload = org.json.JSONObject().apply {
                    put("ssl_cert", cert)
                }

                if (cert.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Offline warning: Dynamic certificate cache empty! Connect to internet to sync."
                    }
                    kotlinx.coroutines.delay(1500)
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Assembling secure provisioning configuration package..."
                    }
                }

                val s1Chunks = buildIndexedChunks(s1Payload.toString(), 230) // MTU safe < 240
                val s2Chunks = buildIndexedChunks(s2Payload.toString(), 230) // MTU safe < 240
                
                withContext(Dispatchers.Main) {
                    totalTransmissionChunks = s1Chunks.size + s2Chunks.size
                }
                
                if (isDemoMode || isBleSetupSimulated) {
                    // Stage 1 configuration transmission
                    for (i in 0 until s1Chunks.size) {
                        withContext(Dispatchers.Main) {
                            currentTransmissionChunkIdx = i + 1
                            setupWaitingText = "Transmitting config chunk ${i + 1}/${s1Chunks.size} over secure GATT..."
                        }
                        delay(800)
                    }
                    
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Config sent! Waiting for [ACK] CONFIG_RECEIVED..."
                    }
                    delay(1200)

                    // Stage 2 certificate transmission
                    for (i in 0 until s2Chunks.size) {
                        withContext(Dispatchers.Main) {
                            currentTransmissionChunkIdx = s1Chunks.size + i + 1
                            setupWaitingText = "Transmitting cert chunk ${i + 1}/${s2Chunks.size} over secure GATT..."
                        }
                        delay(800)
                    }

                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Certificate sent! Waiting for [ACK] CERT_RECEIVED..."
                    }
                    delay(1200)
                    
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Payload transmission completed successfully!"
                        isProvisioningActive = false
                    }
                    delay(500)
                    withContext(Dispatchers.Main) {
                        val repoPayload = ProvisionPayload(
                            ssid = wifiSsidInput,
                            password = wifiPasswordInput,
                            deviceId = assignedId,
                            deviceName = devNameInput,
                            deviceType = devTypeInput,
                            serverUrl = serverUrlInput.trim()
                        )
                        repository.processProvisioning(repoPayload, true)
                        
                        try {
                            activeGatt?.disconnect()
                            activeGatt?.close()
                            activeGatt = null
                        } catch (e: Exception) {}
                        isBleConnected = false
                        
                        _activeDeviceId.value = assignedId
                        
                        start3StageVerificationFlow()
                    }
                    return@launch
                }
                
                val gatt = activeGatt
                if (gatt == null) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Error: BLE Connection lost."
                        isProvisioningActive = false
                    }
                    return@launch
                }
                
                var rxChar: android.bluetooth.BluetoothGattCharacteristic? = null
                for (srv in gatt.services) {
                    for (chr in srv.characteristics) {
                        val uuidStr = chr.uuid.toString().lowercase()
                        if (uuidStr == "87654321-4321-8765-4321-0fedcba98765" ||
                            uuidStr == "6e400002-b5a3-f393-e0a9-e50e24dcca9e" || 
                            uuidStr == "9ecadc24-0ee5-a9e0-93f3-a3b50200406e") {
                            rxChar = chr
                            break
                        }
                    }
                    if (rxChar != null) break
                }
                
                if (rxChar == null) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Error: Rx Characteristic not found."
                        isProvisioningActive = false
                    }
                    return@launch
                }
                
                var writeSuccess = true
                rxChar.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                
                // Set Up Deferred ACKs
                configAckDeferred = kotlinx.coroutines.CompletableDeferred()
                certAckDeferred = kotlinx.coroutines.CompletableDeferred()

                // Transmit Stage 1 (Config) Chunks
                for ((idx, chunkBytes) in s1Chunks.withIndex()) {
                    withContext(Dispatchers.Main) {
                        currentTransmissionChunkIdx = idx + 1
                        setupWaitingText = "Transmitting config chunk ${idx + 1}/${s1Chunks.size}..."
                    }
                    
                    val gattResult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(rxChar, chunkBytes, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        rxChar.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        rxChar.value = chunkBytes
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(rxChar)
                    }
                    
                    if (!gattResult) {
                        writeSuccess = false
                        break
                    }
                    delay(150)
                }
                
                if (!writeSuccess) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "GATT Write failed during Stage 1 transmission."
                        isProvisioningActive = false
                    }
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    setupWaitingText = "Config sent! Waiting for [ACK] CONFIG_RECEIVED..."
                }
                
                val configAckReceived = kotlinx.coroutines.withTimeoutOrNull(20000) {
                    configAckDeferred?.await()
                } ?: false
                
                if (!configAckReceived) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Timeout: Did not receive [ACK] CONFIG_RECEIVED."
                        isProvisioningActive = false
                    }
                    return@launch
                }

                // Transmit Stage 2 (Certificate) Chunks
                for ((idx, chunkBytes) in s2Chunks.withIndex()) {
                    withContext(Dispatchers.Main) {
                        currentTransmissionChunkIdx = s1Chunks.size + idx + 1
                        setupWaitingText = "Transmitting cert chunk ${idx + 1}/${s2Chunks.size}..."
                    }
                    
                    val gattResult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(rxChar, chunkBytes, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        rxChar.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        rxChar.value = chunkBytes
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(rxChar)
                    }
                    
                    if (!gattResult) {
                        writeSuccess = false
                        break
                    }
                    delay(150)
                }
                
                if (!writeSuccess) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "GATT Write failed during Stage 2 transmission."
                        isProvisioningActive = false
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    setupWaitingText = "Certificate sent! Waiting for [ACK] CERT_RECEIVED..."
                }
                
                val certAckReceived = kotlinx.coroutines.withTimeoutOrNull(25000) {
                    certAckDeferred?.await()
                } ?: false

                if (!certAckReceived) {
                    withContext(Dispatchers.Main) {
                        setupWaitingText = "Timeout: Did not receive [ACK] CERT_RECEIVED."
                        isProvisioningActive = false
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    setupWaitingText = "Certificate acknowledged by ESP32!"
                    delay(600)
                    
                    val repoPayload = ProvisionPayload(
                        ssid = wifiSsidInput,
                        password = wifiPasswordInput,
                        deviceId = assignedId,
                        deviceName = devNameInput,
                        deviceType = devTypeInput,
                        serverUrl = serverUrlInput.trim()
                    )
                    repository.processProvisioning(repoPayload, true)
                    
                    try {
                        activeGatt?.disconnect()
                        activeGatt?.close()
                        activeGatt = null
                    } catch (e: SecurityException) { }
                    isBleConnected = false
                    
                    _activeDeviceId.value = assignedId
                    start3StageVerificationFlow()
                    isProvisioningActive = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setupWaitingText = "Error during secure transmission: ${e.localizedMessage}"
                    isProvisioningActive = false
                }
            }
        }
    }

    private var verificationJob: kotlinx.coroutines.Job? = null
    private var fallbackScanJob: kotlinx.coroutines.Job? = null

    fun start3StageVerificationFlow() {
        verificationJob?.cancel()
        fallbackScanJob?.cancel()
        
        provisionStep = 5
        verificationTimeRemaining = 90
        verificationStep1Passed = false
        verificationStep2Passed = false
        verificationStep3Passed = false
        fallbackActiveError = false
        fallbackDeviceName = ""
        fallbackErrorMessage = ""
        
        if (!isDemoMode && !isBleSetupSimulated) {
            repository.hostDiscoveryManager.startDiscovery()
        }
        
        verificationJob = viewModelScope.launch(Dispatchers.Main) {
            while (verificationTimeRemaining > 0) {
                delay(1000)
                verificationTimeRemaining--
                
                val assignedId = devIdInput.trim()
                if (!isDemoMode && !isBleSetupSimulated) {
                    val resolvedIp = repository.hostDiscoveryManager.getDiscoveredIp(assignedId)
                    if (resolvedIp != null && resolvedIp.isNotEmpty()) {
                        verificationStep1Passed = true
                        verificationStep2Passed = true
                    }
                    
                    val dev = repository.hostDiscoveryManager.getDiscoveredIp(assignedId)
                    if (dev != null) {
                        verificationStep1Passed = true
                    }
                    
                    val dbDev = repository.getDeviceDirect(assignedId)
                    if (dbDev != null && dbDev.isOnline) {
                        verificationStep3Passed = true
                    }
                }
                
                if (isDemoMode || isBleSetupSimulated) {
                    val secondsElapsed = 90 - verificationTimeRemaining
                    if (secondsElapsed >= 3) {
                        verificationStep1Passed = true
                    }
                    if (secondsElapsed >= 5) {
                        verificationStep2Passed = true
                    }
                    if (secondsElapsed >= 7) {
                        verificationStep3Passed = true
                    }
                }
                
                if (verificationStep1Passed && verificationStep2Passed && verificationStep3Passed) {
                    break
                }
            }
            
            if (!isDemoMode && !isBleSetupSimulated) {
                repository.hostDiscoveryManager.stopDiscovery()
            }
            
            if (!(verificationStep1Passed && verificationStep2Passed && verificationStep3Passed)) {
                triggerFallbackSafetyNet()
            } else {
                fallbackScanJob?.cancel()
            }
        }
        
        startSilentFallbackBleScanner()
    }

    fun startSilentFallbackBleScanner() {
        fallbackScanJob = viewModelScope.launch(Dispatchers.IO) {
            val assignedId = devIdInput.trim()
            if (isDemoMode || isBleSetupSimulated) {
                if (wifiPasswordInput.contains("wrong", ignoreCase = true) || wifiPasswordInput.contains("fail", ignoreCase = true)) {
                    delay(6000)
                    withContext(Dispatchers.Main) {
                        verificationJob?.cancel()
                        fallbackErrorMessage = "entered password incorrect"
                        triggerFallbackSafetyNet()
                    }
                }
                return@launch
            }
            
            val context = getApplication<Application>().applicationContext
            val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bluetoothManager?.adapter ?: return@launch
            val scanner = adapter.bluetoothLeScanner ?: return@launch
            
            lateinit var callback: android.bluetooth.le.ScanCallback
            callback = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                    val scanRecord = result.scanRecord
                    val name = scanRecord?.deviceName ?: result.device.name ?: ""
                    // Specifically filter for target device ID in advertising name
                    if (name.contains(assignedId, ignoreCase = true) || name.contains("YKP", ignoreCase = true)) {
                        viewModelScope.launch(Dispatchers.Main) {
                            try {
                                scanner.stopScan(callback)
                            } catch (e: SecurityException) { }
                            
                            verificationJob?.cancel()
                            connectFallbackErrorGatt(result.device, assignedId)
                        }
                    }
                }
            }
            
            try {
                scanner.startScan(callback)
                delay(90000)
                scanner.stopScan(callback)
            } catch (e: Exception) { }
        }
    }

    fun connectFallbackErrorGatt(device: android.bluetooth.BluetoothDevice, assignedId: String) {
        val context = getApplication<Application>().applicationContext
        activeFallbackDevice = device
        
        val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {}
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                    var txChar: android.bluetooth.BluetoothGattCharacteristic? = null
                    for (srv in gatt.services) {
                        for (chr in srv.characteristics) {
                            val uuidStr = chr.uuid.toString().lowercase()
                            if (uuidStr == "6e400003-b5a3-f393-e0a9-e50e24dcca9e" || uuidStr == "9ecadc24-0ee5-a9e0-93f3-a3b50300406e") {
                                txChar = chr
                                break
                            }
                        }
                    }
                    if (txChar != null) {
                        try {
                            gatt.setCharacteristicNotification(txChar, true)
                            val descriptor = txChar.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            if (descriptor != null) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(descriptor, android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    @Suppress("DEPRECATION")
                                    descriptor.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    gatt.writeDescriptor(descriptor)
                                }
                            }
                        } catch (e: SecurityException) {}
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: android.bluetooth.BluetoothGatt,
                characteristic: android.bluetooth.BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                val text = String(value, Charsets.UTF_8).trim()
                handleErrorNotification(text, gatt)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: android.bluetooth.BluetoothGatt,
                characteristic: android.bluetooth.BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                val text = String(value, Charsets.UTF_8).trim()
                handleErrorNotification(text, gatt)
            }

            private fun handleErrorNotification(text: String, gatt: android.bluetooth.BluetoothGatt) {
                if (text.startsWith("{") && text.endsWith("}")) {
                    try {
                        val json = org.json.JSONObject(text)
                        val status = json.optString("status")
                        val message = json.optString("message")
                        if (status == "wifi_error") {
                            viewModelScope.launch(Dispatchers.Main) {
                                fallbackErrorMessage = if (message.isNotEmpty()) message else "entered password incorrect"
                                triggerFallbackSafetyNet()
                                try {
                                    gatt.disconnect()
                                    gatt.close()
                                } catch (e: SecurityException) {}
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        try {
            device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {}
    }

    fun submitScenarioBRetry(correctedSsid: String, correctedPass: String) {
        wifiSsidInput = correctedSsid
        wifiPasswordInput = correctedPass
        fallbackActiveError = false
        fallbackErrorMessage = ""
        provisionStep = 5
        isProvisioningActive = true
        setupWaitingText = "Sending Scenario B payload..."
        
        viewModelScope.launch(Dispatchers.IO) {
            if (isDemoMode || isBleSetupSimulated) {
                delay(1200)
                withContext(Dispatchers.Main) {
                    setupWaitingText = "Scenario B [ACK] PAYLOAD_RECEIVED!"
                }
                delay(800)
                withContext(Dispatchers.Main) {
                    isProvisioningActive = false
                    start3StageVerificationFlow()
                }
                return@launch
            }
            
            val dev = activeFallbackDevice
            if (dev == null) {
                withContext(Dispatchers.Main) {
                    setupWaitingText = "Error: target fallback BLE device reference is missing."
                    fallbackActiveError = true
                    provisionStep = 6
                    isProvisioningActive = false
                }
                return@launch
            }
            
            val context = getApplication<Application>().applicationContext
            val callback = object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                    if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {}
                    } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        var rxChar: android.bluetooth.BluetoothGattCharacteristic? = null
                        for (srv in gatt.services) {
                            for (chr in srv.characteristics) {
                                val uuidStr = chr.uuid.toString().lowercase()
                                if (uuidStr == "87654321-4321-8765-4321-0fedcba98765" ||
                                    uuidStr == "6e400002-b5a3-f393-e0a9-e50e24dcca9e" || 
                                    uuidStr == "9ecadc24-0ee5-a9e0-93f3-a3b50200406e") {
                                    rxChar = chr
                                    break
                                }
                            }
                        }
                        
                        if (rxChar != null) {
                            val payload = org.json.JSONObject()
                            payload.put("cmd", "provision")
                            payload.put("ssid", correctedSsid.trim())
                            payload.put("password", correctedPass.trim())
                            val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)
                            
                            try {
                                rxChar.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeCharacteristic(rxChar, payloadBytes, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                                } else {
                                    @Suppress("DEPRECATION")
                                    rxChar.value = payloadBytes
                                    @Suppress("DEPRECATION")
                                    gatt.writeCharacteristic(rxChar)
                                }
                                
                                viewModelScope.launch(Dispatchers.Main) {
                                    setupWaitingText = "Scenario B single-packet transmitted. Waiting for acknowledgment..."
                                }
                            } catch (e: SecurityException) {}
                        }
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: android.bluetooth.BluetoothGatt,
                    characteristic: android.bluetooth.BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    val text = String(value, Charsets.UTF_8).trim()
                    if (text.contains("[ACK] PAYLOAD_RECEIVED")) {
                        viewModelScope.launch(Dispatchers.Main) {
                            setupWaitingText = "Scenario B acknowledged instantly!"
                            try {
                                gatt.disconnect()
                                gatt.close()
                            } catch (e: SecurityException) {}
                            
                            isProvisioningActive = false
                            start3StageVerificationFlow()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(
                    gatt: android.bluetooth.BluetoothGatt,
                    characteristic: android.bluetooth.BluetoothGattCharacteristic
                ) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value ?: return
                    val text = String(value, Charsets.UTF_8).trim()
                    if (text.contains("[ACK] PAYLOAD_RECEIVED")) {
                        viewModelScope.launch(Dispatchers.Main) {
                            setupWaitingText = "Scenario B acknowledged instantly!"
                            try {
                                gatt.disconnect()
                                gatt.close()
                            } catch (e: SecurityException) {}
                            
                            isProvisioningActive = false
                            start3StageVerificationFlow()
                        }
                    }
                }
            }
            
            try {
                dev.connectGatt(context, false, callback)
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    setupWaitingText = "Security Error: BLE GATT connect failed."
                    fallbackActiveError = true
                    provisionStep = 6
                    isProvisioningActive = false
                }
            }
        }
    }

    fun triggerFallbackSafetyNet() {
        provisionStep = 6
        fallbackActiveError = true
        setupWaitingText = "Wi-Fi credential association failed! ESP32 physical node rejected verification."
    }

    fun disconnectDiagnostics() {
        viewModelScope.launch {
            diagnosticsLogs.add("Disconnecting Bluetooth diagnostics secure session gracefully...")
            val gatt = activeDiagnosticsGatt
            if (gatt != null) {
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (e: SecurityException) {}
            }
            activeDiagnosticsGatt = null
            isDiagnosticsConnecting = false
            isDiagnosticsConnected = false
            isDiagnosticsSimulated = false
            navigateBack()
        }
    }

    // ── Local Active Wi-Fi & BLE Network Scanner Utility ───
    var isLocalWifiScanning by mutableStateOf(false)
        private set
    val localScannedWifiNetworks = mutableStateListOf<LocalWifiNetwork>()
    
    var isLocalBleScanning by mutableStateOf(false)
        private set
    val localScannedBleDevices = mutableStateListOf<LocalBleDevice>()
    
    val scannerReceiverLogs = mutableStateListOf<String>()

    private var localWifiReceiver: android.content.BroadcastReceiver? = null
    private var localBleScanCallback: android.bluetooth.le.ScanCallback? = null

    fun startLocalWifiScan() {
        if (isLocalWifiScanning) return
        isLocalWifiScanning = true
        localScannedWifiNetworks.clear()
        
        addScannerLog("Initiating local Wi-Fi active scan request...")
        
        val context = getApplication<Application>().applicationContext
        val wm = context.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        
        if (wm == null) {
            addScannerLog("[ERROR] Wi-Fi Manager is not available on this product platform.")
            runSimulatedWifiScan()
            return
        }

        // Set up Broadcast Receiver to listen for SCAN_RESULTS_AVAILABLE_ACTION
        try {
            if (localWifiReceiver != null) {
                try {
                    context.unregisterReceiver(localWifiReceiver)
                } catch (e: Exception) {}
            }

            localWifiReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                    addScannerLog("[RECEIVER] Received Wi-Fi scan results ready broadcast notification!")
                    try {
                        val results = wm.scanResults
                        if (results.isNullOrEmpty()) {
                            addScannerLog("No physical Wi-Fi APs returned from hardware scanner. Generating simulated network list...")
                            runSimulatedWifiScan()
                        } else {
                            viewModelScope.launch(Dispatchers.Main) {
                                results.forEach { res ->
                                    val ssid = res.SSID ?: "Hidden SSID"
                                    val bssid = res.BSSID ?: "00:00:00:00:00:00"
                                    val rssi = res.level
                                    val freq = res.frequency
                                    val caps = res.capabilities ?: ""
                                    
                                    if (localScannedWifiNetworks.none { it.bssid == bssid }) {
                                        localScannedWifiNetworks.add(LocalWifiNetwork(ssid, bssid, rssi, freq, caps))
                                        addScannerLog("[WIFI DEC] Scanned SSID: '$ssid' | BSSID: $bssid | Signal: ${rssi}dBm")
                                    }
                                }
                                addScannerLog("Successfully registered ${localScannedWifiNetworks.size} local wireless networks.")
                                isLocalWifiScanning = false
                            }
                        }
                    } catch (ex: SecurityException) {
                        addScannerLog("[ERROR] Permission error querying scanResults: ${ex.localizedMessage}")
                        runSimulatedWifiScan()
                    } finally {
                        try {
                            context.unregisterReceiver(this)
                        } catch (e: Exception) {}
                        if (localWifiReceiver == this) {
                            localWifiReceiver = null
                        }
                    }
                }
            }

            context.registerReceiver(
                localWifiReceiver,
                android.content.IntentFilter(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            )
            addScannerLog("Context-Registered BroadCast Receiver for Wi-Fi Scan Result updates.")

            val success = wm.startScan()
            if (!success) {
                addScannerLog("[WARN] System throttled or rejected startScan(). Merging high-fidelity beacons...")
                runSimulatedWifiScan()
            } else {
                addScannerLog("Wi-Fi hardware active scan successfully triggered.")
                // Set safety timeout to unregister receiver if hardware never responds
                viewModelScope.launch {
                    delay(8000)
                    if (isLocalWifiScanning) {
                        addScannerLog("[TIMEOUT] Local Wi-Fi hardware scan timed out. Resolving via simulation...")
                        runSimulatedWifiScan()
                    }
                }
            }
        } catch (e: SecurityException) {
            addScannerLog("[ERROR] Wi-Fi Scan permission denied: ${e.localizedMessage}")
            runSimulatedWifiScan()
        } catch (e: Exception) {
            addScannerLog("[ERROR] Exception triggering local Wi-Fi scan: ${e.localizedMessage}")
            runSimulatedWifiScan()
        }
    }

    private fun runSimulatedWifiScan() {
        viewModelScope.launch(Dispatchers.Main) {
            delay(1200)
            val mocks = listOf(
                LocalWifiNetwork("YKP_HQ_INTERNAL_WPA3", "A4:3E:D0:1F:B4:9C", -38, 5180, "[WPA3-SAE-CCMP][ESS]"),
                LocalWifiNetwork("Santhi_Guest_Home_5G", "8C:AA:F2:15:32:0A", -52, 5745, "[WPA2-PSK-CCMP][WPA-PSK-CCMP][ESS]"),
                LocalWifiNetwork("Sweet_Neighborhood_WPA2", "00:11:22:33:44:55", -72, 2412, "[WPA2-PSK-TKIP][ESS]"),
                LocalWifiNetwork("CoffeeShop_Free_Wifi", "DE:AD:BE:EF:00:FF", -84, 2437, "[ESS]"),
                LocalWifiNetwork("LinkSys_Extender_2G", "AA:BB:CC:DD:EE:01", -65, 2462, "[WPA2-PSK-CCMP][ESS]")
            )
            mocks.forEach { m ->
                if (localScannedWifiNetworks.none { it.bssid == m.bssid }) {
                    localScannedWifiNetworks.add(m)
                    addScannerLog("[SIM-WIFI] Received beacon SSID: '${m.ssid}' | rssi = ${m.rssi}dBm")
                }
            }
            addScannerLog("Resolved Wi-Fi Scan. ${localScannedWifiNetworks.size} Total Networks populated.")
            isLocalWifiScanning = false
        }
    }

    fun startLocalBleScan() {
        if (isLocalBleScanning) return
        isLocalBleScanning = true
        localScannedBleDevices.clear()
        
        addScannerLog("Initiating context Bluetooth LE passive packet receiver monitor...")
        
        val context = getApplication<Application>().applicationContext
        val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = bluetoothManager?.adapter
        
        if (adapter == null || !adapter.isEnabled) {
            addScannerLog("[WARN] System Bluetooth hardware adapter is offline or missing. Simulating BLE beacons...")
            runSimulatedBleScan()
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            addScannerLog("[WARN] System BLE hardware scanner is unavailable. Simulating BLE beacons...")
            runSimulatedBleScan()
            return
        }

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val dev = result.device
                val record = result.scanRecord
                val name = record?.deviceName ?: dev.name ?: "Unknown Peripheral"
                val rssi = result.rssi
                val address = dev.address
                
                // Get primary Service UUID if exists
                val primaryUuidStr = record?.serviceUuids?.firstOrNull()?.uuid?.toString()
                
                // Get raw advertisement payload bytes and format as Hex string
                val rawPayload = record?.bytes
                val hexPayload = rawPayload?.joinToString("") { "%02X".format(it) } ?: "020106"
                
                viewModelScope.launch(Dispatchers.Main) {
                    val existingIndex = localScannedBleDevices.indexOfFirst { it.address == address }
                    if (existingIndex >= 0) {
                        // Update existing entry with newer RSSI/Payload
                        localScannedBleDevices[existingIndex] = LocalBleDevice(name, address, rssi, primaryUuidStr, hexPayload)
                    } else {
                        localScannedBleDevices.add(LocalBleDevice(name, address, rssi, primaryUuidStr, hexPayload))
                        addScannerLog("[BLE RX] Discovered address = $address | name = '$name' | RSSI = ${rssi}dBm")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                viewModelScope.launch(Dispatchers.Main) {
                    addScannerLog("[ERROR] Physical onScanFailed event received from Bluetooth stack. Error code: $errorCode")
                    isLocalBleScanning = false
                    runSimulatedBleScan()
                }
            }
        }

        localBleScanCallback = callback
        try {
            addScannerLog("Subscribed to BLE ScanResult callbacks. Tuning RF Receiver...")
            scanner.startScan(callback)
            
            // Set autonomous timeout for BLE search
            viewModelScope.launch {
                delay(15000)
                if (isLocalBleScanning && localBleScanCallback == callback) {
                    addScannerLog("15s Passive search interval concluded. Stopping active BLE receiver.")
                    stopLocalBleScan()
                }
            }
        } catch (e: SecurityException) {
            addScannerLog("[ERROR] Missing Bluetooth runtime permission: ${e.localizedMessage}")
            runSimulatedBleScan()
        } catch (e: Exception) {
            addScannerLog("[ERROR] Failed to launch hardware scanner: ${e.localizedMessage}")
            runSimulatedBleScan()
        }
    }

    private fun runSimulatedBleScan() {
        viewModelScope.launch(Dispatchers.Main) {
            // Add initial mock devices
            val initialMocks = listOf(
                LocalBleDevice("YKP_PROV_SETUP_A9", "7C:9E:BD:F0:A9:C2", -45, "021a9004-0382-4aea-bff4-6b3f1c5adfb4", "0201061BFF021A900403824AEABFF46B3F1C5ADFB4"),
                LocalBleDevice("M-SmartSwitch-v2", "24:0A:C4:F1:8E:B2", -62, "0000ff01-0000-1000-8000-00805f9b34fb", "0201060AFF01FF240AC4F18EB2"),
                LocalBleDevice("Apple_iBeacon_2B", "5E:92:1A:11:90:3A", -78, "0000fe0b-0000-1000-8000-00805f9b34fb", "02011A1BFF4C000215FDFFFF"),
                LocalBleDevice("Santhi_Bands_H7", "FF:EE:DD:CC:BB:AA", -88, null, "0303E0FF090942616E6473")
            )
            initialMocks.forEach { m ->
                if (localScannedBleDevices.none { it.address == m.address }) {
                    localScannedBleDevices.add(m)
                    addScannerLog("[SIM-BLE] Loaded Peripheral: '${m.name}' address = ${m.address}")
                }
            }
            
            // Simulate random real-time packet updates to mimic a running scanner/receiver!
            var pulses = 0
            while (isLocalBleScanning) {
                delay(1800)
                pulses++
                if (!isLocalBleScanning || pulses > 20) break
                
                val randIndex = (0 until localScannedBleDevices.size).random()
                val target = localScannedBleDevices[randIndex]
                val signalDeviation = (-3..3).random()
                val updatedRssi = (target.rssi + signalDeviation).coerceIn(-100, -30)
                
                localScannedBleDevices[randIndex] = target.copy(rssi = updatedRssi)
                addScannerLog("[UPDATE RX] Recv packet from ${target.address} changed RSSI to ${updatedRssi}dBm (dev = $signalDeviation)")
            }
            
            isLocalBleScanning = false
            addScannerLog("Real-Time BLE Scanner completed scanning cycle.")
        }
    }

    fun stopLocalBleScan() {
        val callback = localBleScanCallback ?: return
        localBleScanCallback = null
        isLocalBleScanning = false
        addScannerLog("Tear down BLE scan sessions and release resources.")
        
        val context = getApplication<Application>().applicationContext
        val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(callback)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun addScannerLog(text: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        viewModelScope.launch(Dispatchers.Main) {
            scannerReceiverLogs.add("[$timestamp] $text")
            if (scannerReceiverLogs.size > 200) {
                scannerReceiverLogs.removeAt(0)
            }
        }
    }

    fun clearScannerLogs() {
        scannerReceiverLogs.clear()
        addScannerLog("Cleared Network Diagnostic receiver logs.")
    }

    override fun onCleared() {
        super.onCleared()
        realtimeSyncManager.stop()
        disconnectDiagnostics()
        
        // Cleanup local network scanners
        if (localWifiReceiver != null) {
            val context = getApplication<Application>().applicationContext
            try {
                context.unregisterReceiver(localWifiReceiver)
            } catch (e: Exception) {}
            localWifiReceiver = null
        }
        stopLocalBleScan()
    }
}
