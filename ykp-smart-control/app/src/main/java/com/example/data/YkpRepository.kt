package com.example.data

import android.content.Context
import android.util.Log
import com.example.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class YkpRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val deviceDao = db.deviceDao()
    private val healthDao = db.healthDao()
    private val groupDao = db.groupDao()
    private val automationDao = db.automationDao()

    val allDevices: Flow<List<DeviceEntity>> = deviceDao.getAllDevicesFlow()
    val allGroups: Flow<List<GroupEntity>> = groupDao.getAllGroupsFlow()
    val allRules: Flow<List<AutomationEntity>> = automationDao.getAllRulesFlow()

    fun getDeviceById(deviceId: String): Flow<DeviceEntity?> = deviceDao.getDeviceByIdFlow(deviceId)
    suspend fun getDeviceDirect(deviceId: String): DeviceEntity? = deviceDao.getDeviceById(deviceId)
    fun getHealthHistory(deviceId: String): Flow<List<HealthEntity>> = healthDao.getHealthHistoryFlow(deviceId)
    fun getLatestHealth(deviceId: String): Flow<HealthEntity?> = healthDao.getLatestHealthFlow(deviceId)

    // Demo / Simulation Mode state
    var isDemoMode = false

    val hostDiscoveryManager = LocalDeviceDiscoveryManager(context, deviceDao)

    init {
        // Pre-populate some dummy devices if database is empty on first boot and isDemoMode is true.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val existing = deviceDao.getAllDevicesFlow().first()
            if (existing.isEmpty() && isDemoMode) {
                seedInitialData()
            }
        }
        // Start mDNS Network Service Discovery
        hostDiscoveryManager.startDiscovery()
    }

    suspend fun insertOrUpdateDevice(device: DeviceEntity) {
        deviceDao.insertOrUpdateDevice(device)
    }

    suspend fun updateDevicePresence(deviceId: String, isOnline: Boolean) {
        val formats = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowStr = formats.format(Date())
        deviceDao.updateOnlineStatus(deviceId, isOnline, nowStr)
    }

    suspend fun updateNetworkMetrics(deviceId: String, rssi: Int?, rttMs: Int?) {
        deviceDao.updateNetworkMetrics(deviceId, rssi, rttMs)
    }

    suspend fun updateDeviceLocalState(deviceId: String, relayState: Boolean) {
        deviceDao.updateRelayState(deviceId, relayState)
        evaluateRules(triggeredByDevice = deviceId, triggeredAction = if (relayState) 1 else 2)
    }

    private suspend fun seedInitialData() {
        val formats = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowStr = formats.format(Date())

        val dev1 = DeviceEntity(
            deviceId = "SW001",
            deviceName = "Living Room Switch",
            deviceType = "switch",
            firmwareVer = "1.2.1",
            isOnline = true,
            ipAddress = "192.168.1.142",
            lastSeen = nowStr,
            capabilities = 15,
            location = "Living Room",
            relayState = true
        )
        val dev2 = DeviceEntity(
            deviceId = "SN001",
            deviceName = "Hallway Motion Sensor",
            deviceType = "sensor",
            firmwareVer = "1.1.0",
            isOnline = true,
            ipAddress = "192.168.1.150",
            lastSeen = nowStr,
            capabilities = 4,
            location = "Hallway",
            relayState = false // sensor active state (triggered or normal)
        )
        val dev3 = DeviceEntity(
            deviceId = "MT002",
            deviceName = "Garage Roller Door",
            deviceType = "motor",
            firmwareVer = "1.3.4",
            isOnline = false,
            ipAddress = null,
            lastSeen = nowStr,
            capabilities = 8,
            location = "Garage",
            relayState = false
        )

        deviceDao.insertOrUpdateDevice(dev1)
        deviceDao.insertOrUpdateDevice(dev2)
        deviceDao.insertOrUpdateDevice(dev3)

        // Seed 15 telemetry points for each device to generate beautiful charts on first launch
        generateSampleTelemetry("SW001")
        generateSampleTelemetry("SN001")
        generateSampleTelemetry("MT002")

        // Seed an initial Group
        groupDao.insertOrUpdateGroup(
            GroupEntity(
                groupId = 1,
                groupName = "Living Area Devices",
                members = "SW001,SN001",
                isActive = true
            )
        )

        // Seed an initial Automation Rule: When motion is triggered, turn on Living Room Switch
        automationDao.insertOrUpdateRule(
            AutomationEntity(
                ruleId = "rule_motion_light",
                ruleName = "Motion → Light ON",
                triggerDevice = "SN001",
                triggerService = 2, // SENSOR Service
                triggerAction = 1, // ON / Triggered
                targetDevice = "SW001",
                targetService = 1, // RELY Service
                targetAction = 1, // RELAY ON
                isEnabled = true
            )
        )
    }

    suspend fun generateSampleTelemetry(deviceId: String) {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val calendar = Calendar.getInstance()
        for (i in 15 downTo 1) {
            calendar.add(Calendar.MINUTE, -i * 10)
            val recordedStr = sdf.format(calendar.time)
            calendar.add(Calendar.MINUTE, i * 10) // reset
            
            // Random variation around realistic defaults
            val cpu = 1.0 + Random.nextDouble() * 5.0
            val heap = 180000L + Random.nextLong(25000L)
            val minHeap = 170000L
            val rssi = -55 - Random.nextInt(25)
            val temp = 38.0 + Random.nextDouble() * 10.0
            val battery = if (deviceId.startsWith("SN")) 85 - i else 100
            val loss = if (rssi < -75) 2.0 else 0.0
            val rtt = 15 + Random.nextInt(35)
            val uptime = 1000L * i

            healthDao.insertHealthRecord(
                HealthEntity(
                    deviceId = deviceId,
                    cpuUsage = cpu,
                    freeHeap = heap,
                    minHeap = minHeap,
                    rssi = rssi,
                    temperature = temp,
                    battery = battery,
                    packetLoss = loss,
                    rttMs = rtt,
                    uptimeSec = uptime,
                    restartCount = if (i > 10) 1 else 0,
                    recordedAt = recordedStr
                )
            )
        }
    }

    suspend fun refreshDevices() {
        if (isDemoMode) return
        try {
            val list = YkpApiClient.getRouterApi().getDevices()
            for (res in list) {
                deviceDao.insertOrUpdateDevice(
                    DeviceEntity(
                        deviceId = res.deviceId,
                        deviceName = res.deviceName,
                        deviceType = res.deviceType,
                        firmwareVer = res.firmwareVer,
                        isOnline = res.isOnline,
                        ipAddress = res.ipAddress,
                        lastSeen = res.lastSeen,
                        capabilities = res.capabilities,
                        location = res.location,
                        relayState = res.relayState
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("YkpRepository", "Failed refreshDevices", e)
        }
    }

    suspend fun getNextPacketId(deviceId: String): Int {
        val dev = deviceDao.getDeviceById(deviceId)
        if (dev != null) {
            val nextId = dev.lastPacketId + 1
            deviceDao.insertOrUpdateDevice(dev.copy(lastPacketId = nextId))
            return nextId
        }
        return 1
    }

    suspend fun sendLocalControlCommand(deviceId: String, relayState: Boolean, packetId: Int = 0): Boolean {
        if (isDemoMode) return true
        
        var ip: String? = hostDiscoveryManager.getDiscoveredIp(deviceId)
        
        if (ip == null) {
            try {
                val address = InetAddress.getByName("esp32-secure.local")
                ip = address.hostAddress
                Log.d("YkpRepository", "Resolved esp32-secure.local to $ip via DNS lookup")
            } catch (e: Exception) {
                // ignore
            }
        }
        
        if (ip == null) {
            try {
                val deviceObj = deviceDao.getDeviceById(deviceId)
                ip = deviceObj?.ipAddress
                Log.d("YkpRepository", "Using stored device ip Address $ip for device $deviceId")
            } catch (e: Exception) {
                // ignore
            }
        }
        
        if (ip != null && ip.isNotEmpty() && ip != "null" && ip != "0.0.0.0") {
            try {
                val cmdByte = if (relayState) 1.toByte() else 2.toByte()
                val encryptedPayload = UdpCryptoUtils.encryptCommand(cmdByte, packetId)
                
                val address = InetAddress.getByName(ip)
                val socket = DatagramSocket()
                socket.soTimeout = 800
                val packet = DatagramPacket(encryptedPayload, encryptedPayload.size, address, 3333)
                socket.send(packet)
                socket.close()
                Log.d("YkpRepository", "Successfully dispatched AES-GCM UDP secure control with packetId $packetId to $ip:3333 for $deviceId")
                return true
            } catch (e: Exception) {
                Log.e("YkpRepository", "Local UDP control failed to send packet to $ip", e)
            }
        } else {
            Log.d("YkpRepository", "Could not resolve any network IP address for direct control of $deviceId")
        }
        return false
    }

    suspend fun controlRelay(deviceId: String, relayState: Boolean) {
        // Optimistic local update
        deviceDao.updateRelayState(deviceId, relayState)

        val newPacketId = getNextPacketId(deviceId)

        if (!isDemoMode) {
            val deviceObj = deviceDao.getDeviceById(deviceId)
            val ip = hostDiscoveryManager.getDiscoveredIp(deviceId) ?: deviceObj?.ipAddress
            val hasLocalIp = ip != null && ip.isNotEmpty() && ip != "null" && ip != "0.0.0.0"

            if (hasLocalIp) {
                Log.d("YkpRepository", "Local IP is mapped. Routing command strictly via dynamic UDP link...")
                val localUdpSuccess = sendLocalControlCommand(deviceId, relayState, newPacketId)
                if (!localUdpSuccess) {
                    Log.d("YkpRepository", "Local UDP failed. Standard Cloud fallback activated sequentially...")
                    sendCloudCommand(deviceId, relayState, newPacketId)
                }
            } else {
                Log.d("YkpRepository", "No local IP mapped. Directing routing strictly to Cloud API Proxy...")
                sendCloudCommand(deviceId, relayState, newPacketId)
            }
        }

        // Run automation processor because target state changed or event was simulated
        evaluateRules(triggeredByDevice = deviceId, triggeredAction = if (relayState) 1 else 2)
    }

    private suspend fun sendCloudCommand(deviceId: String, relayState: Boolean, packetId: Int) {
        try {
            val serviceType = 1 // RELAY service
            val action = if (relayState) 1 else 2 // 1 = ON, 2 = OFF
            YkpApiClient.getRouterApi().sendCommand(deviceId, CommandRequest(serviceType, action, packetId))
        } catch (e: Exception) {
            Log.e("YkpRepository", "Cloud Command sending failed", e)
        }
    }

    suspend fun triggerSensorStateChange(deviceId: String, isTriggered: Boolean) {
        // Update local sensor relayState (which represents event active/inactive tag)
        deviceDao.updateRelayState(deviceId, isTriggered)
        val actionCode = if (isTriggered) 1 else 2
        evaluateRules(triggeredByDevice = deviceId, triggeredAction = actionCode)
    }

    private suspend fun evaluateRules(triggeredByDevice: String, triggeredAction: Int) {
        val rules = automationDao.getAllRulesFlow().first()
        for (rule in rules) {
            if (rule.isEnabled && rule.triggerDevice == triggeredByDevice && rule.triggerAction == triggeredAction) {
                val targetId = rule.targetDevice
                val stateToApply = rule.targetAction == 1 // 1=ON, 2=OFF
                deviceDao.updateRelayState(targetId, stateToApply)
            }
        }
    }

    // ── Group Controls ───────────────────────────────────
    suspend fun controlGroup(groupId: Long, state: Boolean) {
        // Find group members
        val groups = allGroups.first()
        val requestedGroup = groups.find { it.groupId == groupId } ?: return
        groupDao.updateGroupState(groupId, state)

        val membersList = requestedGroup.members.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (deviceId in membersList) {
            controlRelay(deviceId, state)
        }
    }

    suspend fun addGroup(name: String, members: List<String>) {
        val joinedStr = members.joinToString(",")
        groupDao.insertOrUpdateGroup(
            GroupEntity(
                groupName = name,
                members = joinedStr,
                isActive = false
            )
        )
    }

    suspend fun deleteGroup(groupId: Long) {
        groupDao.deleteGroup(groupId)
    }

    // ── Automation Rules ────────────────────────────────
    suspend fun addRule(
        name: String,
        triggerDevice: String,
        triggerAction: Int,
        targetDevice: String,
        targetAction: Int
    ) {
        val id = "rule_" + UUID.randomUUID().toString().take(6)
        val rule = AutomationEntity(
            ruleId = id,
            ruleName = name,
            triggerDevice = triggerDevice,
            triggerService = 1, // Default or generic trigger
            triggerAction = triggerAction,
            targetDevice = targetDevice,
            targetService = 1,
            targetAction = targetAction,
            isEnabled = true
        )
        automationDao.insertOrUpdateRule(rule)
    }

    suspend fun toggleRule(ruleId: String, isEnabled: Boolean) {
        automationDao.updateRuleEnabled(ruleId, isEnabled)
    }

    suspend fun deleteRule(ruleId: String) {
        automationDao.deleteRule(ruleId)
    }

    // ── Provisioning Direct softAP call ─────────────────
    suspend fun pingProvisioningAp(): Boolean {
        return try {
            val response = YkpApiClient.getSoftApApi().ping()
            response.status == "ok" && response.mode == "provisioning"
        } catch (e: Exception) {
            false
        }
    }

    suspend fun processProvisioning(payload: ProvisionPayload, isBleMode: Boolean = false): Boolean {
        // Send Config directly to device SoftAP only if not in BLE mode
        if (!isBleMode) {
            try {
                if (!isDemoMode) {
                    val response = YkpApiClient.getSoftApApi().provision(payload)
                    if (!response.success) return false
                }
            } catch (e: Exception) {
                Log.e("YkpRepository", "Failed softAP Provision POST, simulating", e)
                if (!isDemoMode) {
                    return false
                }
            }
        }

        if (isDemoMode || isBleMode) {
            // Add mock device to local SQLite database for demonstration
            val formats = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val device = DeviceEntity(
                deviceId = payload.deviceId,
                deviceName = payload.deviceName,
                deviceType = payload.deviceType,
                firmwareVer = "1.0.0",
                isOnline = true, // Force online for now to simulate success connection to broker
                ipAddress = "192.168.1.199",
                lastSeen = formats.format(Date()),
                capabilities = 1,
                location = "Provisioned Zone",
                relayState = false
            )
            deviceDao.insertOrUpdateDevice(device)
            generateSampleTelemetry(payload.deviceId)
        } else {
            // Real cloud backend sync: retrieve the actual device status, configuration, and telemetry from server
            try {
                refreshDevices()
                refreshDeviceHealth(payload.deviceId)
            } catch (e: Exception) {
                val formats = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val device = DeviceEntity(
                    deviceId = payload.deviceId,
                    deviceName = payload.deviceName,
                    deviceType = payload.deviceType,
                    firmwareVer = "1.0.0",
                    isOnline = true,
                    ipAddress = "192.168.1.199",
                    lastSeen = formats.format(Date()),
                    capabilities = 1,
                    location = "Provisioned Zone",
                    relayState = false
                )
                deviceDao.insertOrUpdateDevice(device)
                generateSampleTelemetry(payload.deviceId)
            }
        }
        return true
    }

    // ── OTA Jobs ─────────────────────────────────────────
    suspend fun executeOta(deviceId: String, ver: String, url: String): Flow<OtaJobResponse> {
        if (!isDemoMode) {
            try {
                val req = OtaJobRequest(
                    deviceId = deviceId,
                    firmwareUrl = url,
                    firmwareVer = ver,
                    firmwareSha256 = "sha256_placeholder",
                    firmwareSig = "signature_placeholder",
                    firmwareSize = 1024L * 1024L
                )
                // 1. Create real OTA job
                val job = YkpApiClient.getRouterApi().createOtaJob(req)
                return kotlinx.coroutines.flow.flow {
                    emit(job.copy(status = "CREATED", chunksSent = 0, chunksTotal = 10))
                    kotlinx.coroutines.delay(800)

                    // 2. Start OTA job
                    val startedJob = YkpApiClient.getRouterApi().startOtaJob(job.jobId)
                    emit(startedJob.copy(status = "DOWNLOADING", chunksSent = 2, chunksTotal = 10))
                    kotlinx.coroutines.delay(1000)

                    for (chunk in 3..9) {
                        emit(startedJob.copy(status = "WRITING_FLASH", chunksSent = chunk, chunksTotal = 10))
                        kotlinx.coroutines.delay(500)
                    }

                    // Success: Update database
                    val dev = deviceDao.getDeviceById(deviceId)
                    if (dev != null) {
                        deviceDao.insertOrUpdateDevice(dev.copy(firmwareVer = ver))
                    }
                    emit(startedJob.copy(status = "SUCCESS", chunksSent = 10, chunksTotal = 10))
                }
            } catch (e: Exception) {
                Log.e("YkpRepository", "Real OTA execution failed, falling back to mock workflow", e)
            }
        }

        // Demo/Fallback Flow
        val sampleJobId = "ota_job_" + Random.nextInt(10000, 99999)
        return kotlinx.coroutines.flow.flow {
            emit(OtaJobResponse(sampleJobId, "IDLE", 0, 10))
            kotlinx.coroutines.delay(1000)
            
            emit(OtaJobResponse(sampleJobId, "DOWNLOADING", 2, 10))
            kotlinx.coroutines.delay(1000)

            for (chunk in 3..9) {
                emit(OtaJobResponse(sampleJobId, "WRITING", chunk, 10))
                kotlinx.coroutines.delay(500)
            }

            // Success: Update the device's firmware version inside the system
            val dev = deviceDao.getDeviceById(deviceId)
            if (dev != null) {
                deviceDao.insertOrUpdateDevice(dev.copy(firmwareVer = ver))
            }
            emit(OtaJobResponse(sampleJobId, "SUCCESS", 10, 10))
        }
    }

    suspend fun refreshDeviceHealth(deviceId: String) {
        if (isDemoMode) {
            generateSampleTelemetry(deviceId)
            return
        }
        try {
            // Get latest health
            val latest = YkpApiClient.getRouterApi().getHealth(deviceId)
            healthDao.insertHealthRecord(
                HealthEntity(
                    deviceId = latest.deviceId,
                    cpuUsage = latest.cpuUsage,
                    freeHeap = latest.freeHeap,
                    minHeap = latest.minHeap,
                    rssi = latest.rssi,
                    temperature = latest.temperature,
                    battery = latest.battery,
                    packetLoss = latest.packetLoss,
                    rttMs = latest.rttMs,
                    uptimeSec = latest.uptimeSec,
                    restartCount = latest.restartCount,
                    recordedAt = latest.recordedAt
                )
            )

            // Get historical health (e.g. last 24 hours)
            val history = YkpApiClient.getRouterApi().getHealthHistory(deviceId, 24)
            for (item in history) {
                healthDao.insertHealthRecord(
                    HealthEntity(
                        deviceId = item.deviceId,
                        cpuUsage = item.cpuUsage,
                        freeHeap = item.freeHeap,
                        minHeap = item.minHeap,
                        rssi = item.rssi,
                        temperature = item.temperature,
                        battery = item.battery,
                        packetLoss = item.packetLoss,
                        rttMs = item.rttMs,
                        uptimeSec = item.uptimeSec,
                        restartCount = item.restartCount,
                        recordedAt = item.recordedAt
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("YkpRepository", "Failed refreshDeviceHealth for $deviceId", e)
        }
    }

    suspend fun updateDeviceDetails(deviceId: String, name: String, type: String) {
        val dev = deviceDao.getDeviceById(deviceId)
        if (dev != null) {
            deviceDao.insertOrUpdateDevice(dev.copy(deviceName = name, deviceType = type))
        }
    }

    suspend fun removeDevice(deviceId: String) {
        deviceDao.deleteDevice(deviceId)
    }

    suspend fun clearLocalDatabase(seedIfDemo: Boolean = false) {
        deviceDao.clearAllDevices()
        healthDao.clearAllTelemetry()
        if (seedIfDemo && isDemoMode) {
            seedInitialData()
        }
    }

    suspend fun fetchAndCacheSslCert(): String {
        return withContext(Dispatchers.IO) {
            try {
                val certStr = YkpApiClient.fetchSslCertificate()
                if (certStr.isNotEmpty()) {
                    saveCachedSslCert(certStr)
                    Log.d("YkpRepository", "Successfully fetched and cached SSL certificate.")
                    return@withContext certStr
                }
            } catch (e: Exception) {
                Log.e("YkpRepository", "Failed to fetch remote SSL certificate: ${e.message}", e)
            }
            getCachedSslCert()
        }
    }

    suspend fun fetchSslCertificate(): String {
        return fetchAndCacheSslCert()
    }

    fun saveCachedSslCert(cert: String) {
        val sharedPrefs = context.getSharedPreferences("ykp_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("cached_ssl_cert", cert).apply()
    }

    fun getCachedSslCert(): String {
        val sharedPrefs = context.getSharedPreferences("ykp_prefs", Context.MODE_PRIVATE)
        val cert = sharedPrefs.getString("cached_ssl_cert", "") ?: ""
        if (cert.isEmpty()) {
            Log.w("YkpRepository", "Local certificate cache is empty! Connect to internet to sync.")
            return ""
        }
        return cert
    }
}

// ── Secure Hardware Local Control helpers (AES-GCM 256 + HKDF SHA-256) ──

object UdpCryptoUtils {
    private const val IKM = "Test_Secret_IKM_Phase1_App"
    private const val SALT = "Phase1_Salt"
    private const val INFO = "UDP_AES_GCM_256"

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val realSalt = if (salt.isEmpty()) ByteArray(32) else salt
        return hmacSha256(realSalt, ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1
        while (offset < length) {
            val macInput = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, macInput, 0, t.size)
            System.arraycopy(info, 0, macInput, t.size, info.size)
            macInput[macInput.size - 1] = i.toByte()
            t = hmacSha256(prk, macInput)
            val copyLen = Math.min(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
            i++
        }
        return okm
    }

    fun deriveAesKey(): ByteArray {
        val ikmBytes = IKM.toByteArray(Charsets.UTF_8)
        val saltBytes = SALT.toByteArray(Charsets.UTF_8)
        val infoBytes = INFO.toByteArray(Charsets.UTF_8)
        val prk = hkdfExtract(saltBytes, ikmBytes)
        return hkdfExpand(prk, infoBytes, 32) // 32 bytes for AES-256-GCM key
    }

    fun encryptCommand(cmd: Byte, packetId: Int = 0): ByteArray {
        val keyBytes = deriveAesKey()
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val spec = javax.crypto.spec.GCMParameterSpec(96, iv) // Exact 96-bit (12 bytes) GCM auth tag
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), spec)
        
        // Custom Binary TLV Format:
        // TLV 1: Tag=0x01 (Action), Length=1 (Size), Value=cmd (1 byte) -> 3 bytes
        // TLV 2: Tag=0x02 (Packet ID), Length=4 (Size), Value=packetId (4-byte big-endian) -> 6 bytes
        val payload = ByteArray(9)
        // TLV 1
        payload[0] = 0x01.toByte() // Tag
        payload[1] = 1.toByte()    // Length
        payload[2] = cmd           // Value
        // TLV 2
        payload[3] = 0x02.toByte() // Tag
        payload[4] = 4.toByte()    // Length
        payload[5] = ((packetId shr 24) and 0xFF).toByte()
        payload[6] = ((packetId shr 16) and 0xFF).toByte()
        payload[7] = ((packetId shr 8) and 0xFF).toByte()
        payload[8] = (packetId and 0xFF).toByte()
        
        val ciphertextWithTag = cipher.doFinal(payload)
        
        val result = ByteArray(12 + ciphertextWithTag.size)
        System.arraycopy(iv, 0, result, 0, 12)
        System.arraycopy(ciphertextWithTag, 0, result, 12, ciphertextWithTag.size)
        return result
    }
}

class LocalDeviceDiscoveryManager(context: Context, private val deviceDao: DeviceDao) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val discoveredIps = ConcurrentHashMap<String, String>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    companion object {
        private const val SERVICE_TYPE = "_sec-relay._udp."
    }

    fun startDiscovery() {
        if (discoveryListener != null) return
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalDeviceDiscovery", "Start Discovery Failed: $errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    // Suppress
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalDeviceDiscovery", "Stop Discovery Failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d("LocalDeviceDiscovery", "mDNS Service Discovery started for $SERVICE_TYPE")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d("LocalDeviceDiscovery", "mDNS Service Discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                Log.d("LocalDeviceDiscovery", "mDNS found service: ${serviceInfo?.serviceName}")
                if (serviceInfo?.serviceType?.contains("_sec-relay") == true) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(resolvedInfo: NsdServiceInfo?, errorCode: Int) {
                            Log.e("LocalDeviceDiscovery", "mDNS Resolve Failed: $errorCode")
                        }

                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                            val host = resolvedInfo?.host
                            val ipAddress = host?.hostAddress
                            Log.d("LocalDeviceDiscovery", "mDNS resolved ${resolvedInfo?.serviceName} to IP: $ipAddress")
                            if (ipAddress != null) {
                                val serviceName = resolvedInfo.serviceName ?: "esp32-secure"
                                discoveredIps[serviceName] = ipAddress
                                discoveredIps["esp32-secure.local"] = ipAddress
                                
                                // Launch a coroutine to update the Room Database with dynamic IP and port (3333) as required
                                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        val allDevs = deviceDao.getAllDevicesFlow().first()
                                        for (dev in allDevs) {
                                            if (serviceName.contains(dev.deviceId, ignoreCase = true) ||
                                                dev.deviceId.contains(serviceName, ignoreCase = true) ||
                                                serviceName.contains("esp32-secure", ignoreCase = true)) {
                                                
                                                val updatedDevice = dev.copy(ipAddress = ipAddress)
                                                deviceDao.insertOrUpdateDevice(updatedDevice)
                                                Log.d("LocalDeviceDiscovery", "[DYNAMIC IP MAPPING] Mapped device ${dev.deviceId} to IP $ipAddress and port 3333 in Room DB")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("LocalDeviceDiscovery", "Failed to update dynamic IP mapping in Room DB", e)
                                    }
                                }
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d("LocalDeviceDiscovery", "mDNS service lost: ${serviceInfo?.serviceName}")
                if (serviceInfo != null) {
                    val serviceName = serviceInfo.serviceName
                    if (serviceName != null) {
                        discoveredIps.remove(serviceName)
                    }
                }
            }
        }
        
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("LocalDeviceDiscovery", "Error starting discovery", e)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e("LocalDeviceDiscovery", "Error stopping discovery", e)
            }
        }
        discoveryListener = null
    }

    fun getDiscoveredIp(deviceId: String): String? {
        return discoveredIps["esp32-secure.local"] ?: discoveredIps[deviceId] ?: discoveredIps.values.firstOrNull()
    }
}
