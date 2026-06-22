package com.example.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface YkpBleListener {
    fun onConnectionStateChanged(isConnected: Boolean, progressMessage: String)
    fun onNotificationReceived(json: JSONObject)
    fun onError(message: String)
}

@SuppressLint("MissingPermission")
class YkpBleManager(
    private val context: Context,
    private val listener: YkpBleListener,
    private val scope: CoroutineScope
) {
    private val tag = "YkpBleManager"

    // GATTS Constants
    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val DEFAULT_BLE_SESSION_PAIRING_KEY = "YKP_v3_SECURE_SESSION_KEY_2026".toByteArray(Charsets.UTF_8)

    // Active BLE Objects
    var activeGatt: BluetoothGatt? = null
        private set
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    // Protocol States
    var sessionId: String = ""
        private set
    var seq: Int = 1
        private set
    private var hmacKey: ByteArray = ByteArray(32)

    private val notificationBuffer = StringBuilder()
    private var isMtuNegotiated = false

    // Connection tracking for 90-second reconnect
    private var targetDeviceAddress: String? = null
    private var isSessionActive = false
    private var reconnectJob: Job? = null
    private val reconnectWindowMs = 90000L
    private var sessionStartTime = 0L
    private var pingJob: Job? = null

    init {
        // Initialize HMAC key with default salt (4 zeros)
        deriveHmacKey(0)
    }

    fun startSession(address: String) {
        targetDeviceAddress = address
        isSessionActive = true
        sessionStartTime = System.currentTimeMillis()
        connectGattInternal(address)
    }

    fun stopSession() {
        isSessionActive = false
        reconnectJob?.cancel()
        targetDeviceAddress = null
        disconnectGatt()
    }

    private fun connectGattInternal(address: String) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Bluetooth is not available or disabled")
            return
        }

        try {
            val device = adapter.getRemoteDevice(address)
            Log.d(tag, "Connecting to $address. Bond status: ${device.bondState}")

            listener.onConnectionStateChanged(false, "Connecting to BLE device...")

            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                listener.onConnectionStateChanged(false, "Initiating pairing / bonding...")
                device.createBond()
            }

            activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(tag, "connectGattInternal error: ${e.message}", e)
            listener.onError("Connection failed: ${e.localizedMessage}")
            triggerReconnectIfNecessary()
        }
    }

    private fun disconnectGatt() {
        stopPingLoop()
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
            activeGatt = null
        } catch (e: Exception) {
            Log.e(tag, "Error disconnecting GATT: ${e.message}")
        }
        rxChar = null
        txChar = null
        isMtuNegotiated = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE_DIAG", "Connected. Requesting 512-byte MTU...")
                reconnectJob?.cancel() // Cancel any pending reconnect on successful connection
                try {
                    gatt.requestMtu(512)
                } catch (e: Exception) {
                    Log.e("BLE_DIAG", "Failed to request MTU: ${e.message}")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.e("BLE_DIAG", "Disconnected. Status code: $status")
                isMtuNegotiated = false
                rxChar = null
                txChar = null
                scope.launch {
                    listener.onConnectionStateChanged(false, "BLE disconnected.")
                }
                triggerReconnectIfNecessary(status)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE_DIAG", "MTU successfully negotiated to: $mtu bytes")
                Log.d("BLE_DIAG", "Now discovering services...")
                isMtuNegotiated = true
                scope.launch {
                    listener.onConnectionStateChanged(false, "MTU $mtu negotiation successful. Discovering services...")
                }
            } else {
                Log.e("BLE_DIAG", "MTU request failed with status: $status")
                isMtuNegotiated = false
                scope.launch {
                    listener.onConnectionStateChanged(false, "MTU negotiation failed ($status). Discovering services anyway...")
                }
            }
            try {
                gatt.discoverServices()
            } catch (e: Exception) {
                Log.e(tag, "Failed to discover services: ${e.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    rxChar = service.getCharacteristic(RX_CHAR_UUID)
                    txChar = service.getCharacteristic(TX_CHAR_UUID)
                }

                // Fallback / adaptive searching just in case names or custom UUID mappings vary
                if (rxChar == null || txChar == null) {
                    for (srv in gatt.services) {
                        for (chr in srv.characteristics) {
                            val uuidStr = chr.uuid.toString().lowercase()
                            if (uuidStr == "6e400002-b5a3-f393-e0a9-e50e24dcca9e") {
                                rxChar = chr
                            } else if (uuidStr == "6e400003-b5a3-f393-e0a9-e50e24dcca9e") {
                                txChar = chr
                            }
                        }
                    }
                }

                if (rxChar != null && txChar != null) {
                    Log.d(tag, "Primary service and characteristics located.")
                    try {
                        gatt.setCharacteristicNotification(txChar, true)
                        val descriptor = txChar!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to enable notification: ${e.message}")
                    }
                } else {
                    Log.e(tag, "RX or TX characteristic not found.")
                    scope.launch {
                        listener.onError("RX or TX characteristics not found on service")
                    }
                }
            } else {
                Log.e(tag, "Service discovery failed with status $status")
                scope.launch {
                    listener.onError("Service discovery failed with status $status")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d("BLE_DIAG", "Descriptor write status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (isMtuNegotiated) {
                    Log.d("BLE_DIAG", "GATT client completely ready! MTU 512 negotiated.")
                } else {
                    Log.w("BLE_DIAG", "GATT client ready, but MTU negotiation status was not success.")
                }
                scope.launch {
                    listener.onConnectionStateChanged(true, "Ready to provision")
                }
                startPingLoop()
            } else {
                Log.e("BLE_DIAG", "Failed to subscribe to Tx notifications: $status")
                scope.launch {
                    listener.onError("Failed to subscribe to Tx notifications: $status")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            val chunkStr = String(value, Charsets.UTF_8)
            synchronized(notificationBuffer) {
                notificationBuffer.append(chunkStr)
                if (notificationBuffer.contains("\n")) {
                    val lines = notificationBuffer.split("\n")
                    for (i in 0 until lines.size - 1) {
                        val line = lines[i].trim()
                        if (line.isNotEmpty()) {
                            Log.d(tag, "Incoming line packet: $line")
                            handleIncomingLine(line)
                        }
                    }
                    notificationBuffer.clear()
                    notificationBuffer.append(lines.last())
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val chunkStr = String(value, Charsets.UTF_8)
            synchronized(notificationBuffer) {
                notificationBuffer.append(chunkStr)
                if (notificationBuffer.contains("\n")) {
                    val lines = notificationBuffer.split("\n")
                    for (i in 0 until lines.size - 1) {
                        val line = lines[i].trim()
                        if (line.isNotEmpty()) {
                            Log.d(tag, "Incoming line packet: $line")
                            handleIncomingLine(line)
                        }
                    }
                    notificationBuffer.clear()
                    notificationBuffer.append(lines.last())
                }
            }
        }
    }

    private fun handleIncomingLine(line: String) {
        scope.launch {
            try {
                if (line.startsWith("{")) {
                    val json = JSONObject(line)
                    
                    // Parse session_id from scan_results or any incoming notify payload
                    if (json.has("session_id")) {
                        val sId = json.get("session_id")
                        val sIdStr = sId.toString()
                        if (sIdStr.isNotEmpty() && sIdStr != "0" && sIdStr != sessionId) {
                            sessionId = sIdStr
                            deriveHmacKey(sId)
                            Log.d(tag, "Updated session ID to $sessionId, derived HMAC Key.")
                        }
                    }

                    listener.onNotificationReceived(json)
                } else {
                    Log.w(tag, "Received non-JSON notification line: $line")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error handling incoming notification: ${e.message}")
            }
        }
    }

    private fun triggerReconnectIfNecessary(status: Int = -1) {
        stopPingLoop()
        if (!isSessionActive) return
        val elapsed = System.currentTimeMillis() - sessionStartTime
        if (elapsed > reconnectWindowMs) {
            Log.w(tag, "Exceeded 90-second reconnect window, giving up.")
            scope.launch {
                listener.onError("Provisioning session timed out after disconnect.")
            }
            return
        }

        val remainingTimeSec = ((reconnectWindowMs - elapsed) / 1000).coerceAtLeast(0)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (status == 19 || status == 0x13) {
                Log.w("BLE_DIAG", "Rate limiting detected (disconnect 0x13). Waiting 15 seconds cooldown...")
            } else {
                Log.d("BLE_DIAG", "Disconnection detected. Waiting 15 seconds to reconnect gracefully...")
            }
            delay(15000)
            targetDeviceAddress?.let { address ->
                listener.onConnectionStateChanged(false, "BLE disconnected. Trying to reconnect ($remainingTimeSec sec left)...")
                connectGattInternal(address)
            }
        }
    }

    private fun startPingLoop() {
        stopPingLoop()
        pingJob = scope.launch {
            while (isActive && activeGatt != null) {
                delay(5000)
                if (activeGatt != null && sessionId.isNotEmpty() && sessionId != "0") {
                    val pingPayload = JSONObject().apply {
                        put("cmd", "ping")
                    }
                    Log.d(tag, "Sending keep-alive ping...")
                    writeCommand(pingPayload)
                }
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    // --- SECURE CRYPTO FUNCTIONS ---

    fun deriveHmacKey(sessionIdValue: Any) {
        try {
            // Salt: The UTF-8 string byte representation of the decimal session ID
            val salt = sessionIdValue.toString().toByteArray(Charsets.UTF_8)
            
            // IKM: Static 32-byte pairing key of 0x01 bytes matching firmware mock_pairing_key
            val pairingKey = ByteArray(32) { 0x01.toByte() }
            
            // HKDF-Extract: PRK = HMAC-SHA256(salt, pairingKey)
            val prk = hmacSha256(salt, pairingKey)
            
            // HKDF-Expand: OKM = HMAC-SHA256(PRK, info | 0x01)
            val info = "YKP_HMAC_KEY".toByteArray(Charsets.UTF_8)
            val infoWithIndex = ByteArray(info.size + 1)
            System.arraycopy(info, 0, infoWithIndex, 0, info.size)
            infoWithIndex[info.size] = 0x01.toByte()
            
            hmacKey = hmacSha256(prk, infoWithIndex)
        } catch (e: Exception) {
            e.printStackTrace()
            hmacKey = ByteArray(32)
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun computeHmac(data: String): String {
        return try {
            val rawHmac = hmacSha256(hmacKey, data.toByteArray(Charsets.UTF_8))
            rawHmac.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    // --- SECURE WRITE API ---

    fun writeCommand(payload: JSONObject): Boolean {
        if (sessionId.isEmpty() || sessionId == "0") {
            Log.w("BLE_DIAG", "Cannot write command: session_id is not synchronized yet! Status: Dropped command ${payload.optString("cmd")}")
            return false
        }
        val gatt = activeGatt ?: return false
        val char = rxChar ?: return false

        // Always check and inject session_id and seq if they are not already set
        try {
            if (!payload.has("session_id")) {
                if (sessionId.isNotEmpty()) {
                    // Try parsing as number if digit, else keep string
                    val numVal = sessionId.toIntOrNull()
                    if (numVal != null) {
                        payload.put("session_id", numVal)
                    } else {
                        payload.put("session_id", sessionId)
                    }
                } else {
                    payload.put("session_id", 0)
                }
            }
            if (!payload.has("seq")) {
                payload.put("seq", seq++)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to modify JSON payloads: ${e.message}")
        }

        val serialized = payload.toString()
        val hmac = computeHmac(serialized)
        val signedStr = "$serialized|$hmac"

        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val payloadBytes = signedStr.toByteArray(Charsets.UTF_8)

        val success: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(char, payloadBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            success = status == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payloadBytes
            @Suppress("DEPRECATION")
            success = gatt.writeCharacteristic(char)
        }

        Log.d(tag, "Sent packet: '$signedStr' - success=$success")
        return success
    }
}
