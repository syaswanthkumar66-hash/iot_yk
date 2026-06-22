package com.ykp.smartcontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

// Data Model matching Compose UI expectations
data class WifiNetwork(
    val ssid: String,
    val rssi: Int,
    val auth: Int = 3
)

/**
 * 1. Cryptographic Security Helpers
 */
object BleSecurityHelper {
    
    // Simple HKDF-Extract and Expand implementation for HmacSHA256
    fun deriveHmacKey(pairingKey: ByteArray, sessionId: String): ByteArray {
        try {
            val salt = sessionId.toByteArray(StandardCharsets.UTF_8)
            
            // HKDF-Extract
            val macExtract = Mac.getInstance("HmacSHA256")
            macExtract.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = macExtract.doFinal(pairingKey)
            
            // HKDF-Expand (using info = "YKP_HMAC_KEY" and length = 32 bytes)
            val info = "YKP_HMAC_KEY".toByteArray(StandardCharsets.UTF_8)
            val macExpand = Mac.getInstance("HmacSHA256")
            macExpand.init(SecretKeySpec(prk, "HmacSHA256"))
            macExpand.update(info)
            macExpand.update(0x01.toByte())
            return macExpand.doFinal()
        } catch (e: Exception) {
            e.printStackTrace()
            return ByteArray(32) // Fallback empty key (fails validation securely)
        }
    }

    // Computes HMAC-SHA256 signature in hexadecimal format
    fun computeHmac(data: String, key: ByteArray): String {
        return try {
            val sha256HMAC = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(key, "HmacSHA256")
            sha256HMAC.init(secretKey)
            val hash = sha256HMAC.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            val hexString = StringBuilder()
            for (b in hash) {
                val hex = Integer.toHexString(0xff and b.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            hexString.toString()
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * 2. Complete BLE Manager implementing Spec v3
 */
@SuppressLint("MissingPermission")
class YkpBleManager(
    private val context: Context,
    private val onNotificationReceived: (String) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var mtuNegotiated = false

    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private val notificationBuffer = StringBuilder()

    // Reconnection tracking
    private var isSessionActive = false
    private var targetDevice: BluetoothDevice? = null
    private var sessionStartTime = 0L
    private var reconnectJob: Job? = null
    private val reconnectWindowMs = 90000L
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun connectDevice(device: BluetoothDevice) {
        targetDevice = device
        isSessionActive = true
        sessionStartTime = System.currentTimeMillis()
        mtuNegotiated = false
        connectGattInternal()
    }

    private fun connectGattInternal() {
        val device = targetDevice ?: return
        reconnectJob?.cancel()
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        isSessionActive = false
        reconnectJob?.cancel()
        targetDevice = null
        disconnectGattOnly()
    }

    private fun disconnectGattOnly() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxChar = null
        txChar = null
        mtuNegotiated = false
    }

    fun writeCommand(payloadJson: String, hmacKey: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val rx = rxChar ?: return
        
        // Spec v3: Signed payload formatting -> <JSON>|<HMAC>
        val hmac = BleSecurityHelper.computeHmac(payloadJson, hmacKey)
        val completeFrame = "$payloadJson|$hmac\n"
        
        rx.value = completeFrame.toByteArray(StandardCharsets.UTF_8)
        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt.writeCharacteristic(rx)
    }

    private fun triggerReconnect(status: Int = -1) {
        if (!isSessionActive) return
        val elapsed = System.currentTimeMillis() - sessionStartTime
        if (elapsed >= reconnectWindowMs) {
            isSessionActive = false
            targetDevice = null
            return
        }
        reconnectJob?.cancel()
        reconnectJob = managerScope.launch {
            if (status == 19 || status == 0x13) {
                android.util.Log.w("BLE_DIAG", "Rate limiting detected (disconnect 0x13). Waiting 15 seconds cooldown...")
            } else {
                android.util.Log.d("BLE_DIAG", "Disconnection detected. Waiting 15 seconds to reconnect gracefully...")
            }
            delay(15000)
            if (isSessionActive) {
                connectGattInternal()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                android.util.Log.d("BLE_DIAG", "Connected. Requesting 512-byte MTU...")
                reconnectJob?.cancel()
                // Request MTU first! Do not declare connected until descriptor subscription succeeds.
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                android.util.Log.e("BLE_DIAG", "Disconnected. Status code: $status")
                onConnectionStateChanged(false)
                disconnectGattOnly()
                triggerReconnect(status)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                android.util.Log.d("BLE_DIAG", "MTU successfully negotiated to: $mtu bytes")
                android.util.Log.d("BLE_DIAG", "Now discovering services...")
                mtuNegotiated = true
                gatt.discoverServices()
            } else {
                android.util.Log.e("BLE_DIAG", "MTU request failed with status: $status")
                mtuNegotiated = false
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                rxChar = service?.getCharacteristic(RX_CHAR_UUID)
                txChar = service?.getCharacteristic(TX_CHAR_UUID)
                
                if (txChar != null) {
                    gatt.setCharacteristicNotification(txChar, true)
                    val descriptor = txChar!!.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            android.util.Log.d("BLE_DIAG", "Descriptor write status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (mtuNegotiated) {
                    android.util.Log.d("BLE_DIAG", "GATT client completely ready! MTU 512 negotiated.")
                } else {
                    android.util.Log.w("BLE_DIAG", "GATT client ready, but MTU negotiation status was not success.")
                }
                onConnectionStateChanged(true)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val incomingChunk = String(characteristic.value ?: ByteArray(0), StandardCharsets.UTF_8)
                notificationBuffer.append(incomingChunk)
                
                // Read complete lines ended by newline
                if (notificationBuffer.contains("\n")) {
                    val lines = notificationBuffer.split("\n")
                    for (i in 0 until lines.size - 1) {
                        onNotificationReceived(lines[i].trim())
                    }
                    val lastPart = lines.last()
                    notificationBuffer.clear()
                    notificationBuffer.append(lastPart)
                }
            }
        }
    }
}

/**
 * 3. Reactive Jetpack Compose ViewModel
 */
class YkpViewModel(context: Context) : ViewModel() {
    
    // Compose reactive UI variables
    var setupWaitingText by mutableStateOf("Ready to connect")
        private set
    var isWifiScanning by mutableStateOf(false)
        private set
    val scannedWifiNetworks = mutableStateListOf<WifiNetwork>()
    var isDeviceConnected by mutableStateOf(false)
        private set
    var isReadyToCommit by mutableStateOf(false)
        private set
    var isSessionSynced by mutableStateOf(false)
        private set

    private var bleManager: YkpBleManager? = null
    
    // Dynamic Session & Security State
    private var sessionId: Long = 0
    private var seq = 1
    private var derivedHmacKey = ByteArray(32)
    private var targetDeviceIp = "192.168.4.1"
    private var activeUdpPort = 47808

    init {
        bleManager = YkpBleManager(
            context = context,
            onNotificationReceived = { data -> handleBleNotification(data) },
            onConnectionStateChanged = { connected ->
                viewModelScope.launch(Dispatchers.Main) {
                    isDeviceConnected = connected
                    if (!connected) {
                        isSessionSynced = false
                        sessionId = 0
                        setupWaitingText = "Disconnected"
                    } else {
                        setupWaitingText = "Device connected. Syncing session ID..."
                    }
                }
            }
        )
    }

    fun connectDevice(device: BluetoothDevice) {
        setupWaitingText = "Connecting to BLE..."
        bleManager?.connectDevice(device)
    }

    fun scanWifi() {
        if (!isDeviceConnected) return
        if (!isSessionSynced || sessionId == 0L) {
            setupWaitingText = "Cannot scan: Waiting for session ID sync..."
            return
        }
        isWifiScanning = true
        scannedWifiNetworks.clear()
        
        viewModelScope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("cmd", "rescan")
                put("session_id", sessionId)
                put("seq", seq++)
            }
            bleManager?.writeCommand(payload.toString(), derivedHmacKey)
        }
    }

    fun provisionDevice(wifiSsid: String, wifiPass: String, serverUrl: String, caCert: String, targetPort: Int, udpPort: Int) {
        if (!isSessionSynced || sessionId == 0L) {
            setupWaitingText = "Cannot provision: Waiting for session ID sync..."
            return
        }
        activeUdpPort = udpPort
        viewModelScope.launch(Dispatchers.IO) {
            // Step 1: Send credentials parameters
            val credsPayload = JSONObject().apply {
                put("cmd", "send_creds")
                put("session_id", sessionId)
                put("seq", seq++)
                put("ssid", wifiSsid)
                put("psk", wifiPass)
                put("host", serverUrl)
                put("port", targetPort)
                put("udp_port", udpPort)
            }
            bleManager?.writeCommand(credsPayload.toString(), derivedHmacKey)
            
            // Pacing delay
            Thread.sleep(500)

            // Step 2: Send CA Certificate in chunks
            if (caCert.isNotEmpty()) {
                val chunkSize = 200
                var chunkIndex = 1
                var startIndex = 0
                while (startIndex < caCert.length) {
                    val endIndex = min(startIndex + chunkSize, caCert.length)
                    val segment = caCert.substring(startIndex, endIndex)
                    val certPayload = JSONObject().apply {
                        put("cmd", "set_cert")
                        put("chunk", chunkIndex)
                        put("data", segment)
                    }
                    bleManager?.writeCommand(certPayload.toString(), derivedHmacKey)
                    Thread.sleep(300) // Pacing delay to prevent ESP32 ring buffer drop
                    startIndex = endIndex
                    chunkIndex++
                }
            }

            // Step 3: Trigger commit validation sequence
            val commitPayload = JSONObject().apply {
                put("cmd", "commit")
                put("session_id", sessionId)
                put("seq", seq++)
            }
            bleManager?.writeCommand(commitPayload.toString(), derivedHmacKey)
        }
    }

    fun confirmSaveAndReboot() {
        if (!isSessionSynced || sessionId == 0L) {
            setupWaitingText = "Cannot confirm: Waiting for session ID sync..."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val confirmPayload = JSONObject().apply {
                put("cmd", "confirm_provision")
                put("session_id", sessionId)
                put("seq", seq++)
            }
            bleManager?.writeCommand(confirmPayload.toString(), derivedHmacKey)
        }
    }

    private fun handleBleNotification(data: String) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                if (data.startsWith("{")) {
                    val json = JSONObject(data)
                    
                    if (json.has("status")) {
                        val status = json.getString("status")
                        when (status) {
                            "scan_results" -> {
                                isWifiScanning = false
                                sessionId = json.getLong("session_id")
                                isSessionSynced = true
                                
                                // Mock static pairing key of 32 bytes for HKDF derivation
                                val mockPairingKey = ByteArray(32) { 0x01.toByte() }
                                derivedHmacKey = BleSecurityHelper.deriveHmacKey(mockPairingKey, sessionId.toString())
                                
                                val networks = json.getJSONArray("networks")
                                for (i in 0 until networks.length()) {
                                    val net = networks.getJSONObject(i)
                                    scannedWifiNetworks.add(
                                        WifiNetwork(ssid = net.getString("ssid"), rssi = net.getInt("rssi"))
                                    )
                                }
                                setupWaitingText = "Wi-Fi scan finished. Select network."
                                sendScanAck()
                            }
                            "creds_ok" -> {
                                val nonce = json.getString("udp_nonce")
                                setupWaitingText = "Credentials accepted. Handshaking Wi-Fi..."
                            }
                            "wifi_ok" -> {
                                targetDeviceIp = json.getString("ip")
                                setupWaitingText = "Wi-Fi connected successfully! Resolving SSL..."
                            }
                            "wifi_fail" -> {
                                setupWaitingText = "Wi-Fi Association Failed: " + json.getString("reason")
                            }
                            "ssl_progress" -> {
                                setupWaitingText = "SSL Connection validation in progress..."
                            }
                            "ssl_done" -> {
                                val result = json.getString("result")
                                setupWaitingText = "SSL handshake status: $result"
                            }
                            "udp_ready" -> {
                                setupWaitingText = "UDP Socket ready on device. Sending subnet probe..."
                                triggerUdpSubnetProbe(json.optString("udp_nonce", ""))
                            }
                            "udp_ok" -> {
                                setupWaitingText = "UDP subnet probe reachability confirmed!"
                            }
                            "report" -> {
                                isReadyToCommit = json.getBoolean("ready_to_commit")
                                val sslStatus = json.getString("ssl_status")
                                setupWaitingText = if (isReadyToCommit) {
                                    "Report: SUCCESS ($sslStatus). Confirm to reboot."
                                } else {
                                    "Report: FAILED ($sslStatus). Check your configuration."
                                }
                                sendReportAck()
                            }
                            "commit_ok" -> {
                                setupWaitingText = "Configuration saved successfully. Device rebooting..."
                                bleManager?.disconnect()
                            }
                            "nvs_rollback" -> {
                                setupWaitingText = "Error: Staging transaction rejected. Config rolled back."
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendScanAck() {
        if (!isSessionSynced || sessionId == 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("cmd", "scan_ack")
                put("session_id", sessionId)
                put("seq", seq++)
            }
            bleManager?.writeCommand(payload.toString(), derivedHmacKey)
        }
    }

    private fun sendReportAck() {
        if (!isSessionSynced || sessionId == 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("cmd", "report_ack")
                put("session_id", sessionId)
                put("seq", seq++)
            }
            bleManager?.writeCommand(payload.toString(), derivedHmacKey)
        }
    }

    private fun triggerUdpSubnetProbe(nonce: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                val payload = "YKP_PROBE:$nonce".toByteArray(StandardCharsets.UTF_8)
                val address = InetAddress.getByName(targetDeviceIp)
                val packet = DatagramPacket(payload, payload.size, address, activeUdpPort)
                
                for (i in 0 until 3) {
                    socket.send(packet)
                    Thread.sleep(100)
                }
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager?.disconnect()
    }
}
