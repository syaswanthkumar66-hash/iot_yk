package com.example.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CommandRequest(
    @Json(name = "service") val service: Int,
    @Json(name = "action") val action: Int,
    @Json(name = "packet_id") val packetId: Int? = null
)

@JsonClass(generateAdapter = true)
data class CommandResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class HealthResponse(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "cpu_usage") val cpuUsage: Double,
    @Json(name = "free_heap") val freeHeap: Long,
    @Json(name = "min_heap") val minHeap: Long,
    @Json(name = "rssi") val rssi: Int,
    @Json(name = "temperature") val temperature: Double,
    @Json(name = "battery") val battery: Int = 100,
    @Json(name = "packet_loss") val packetLoss: Double = 0.0,
    @Json(name = "rtt_ms") val rttMs: Int = 10,
    @Json(name = "uptime_sec") val uptimeSec: Long,
    @Json(name = "restart_count") val restartCount: Int,
    @Json(name = "recorded_at") val recordedAt: String
)

@JsonClass(generateAdapter = true)
data class ProvisionPayload(
    @Json(name = "ssid") val ssid: String,
    @Json(name = "password") val password: String,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "device_name") val deviceName: String,
    @Json(name = "device_type") val deviceType: String,
    @Json(name = "server_url") val serverUrl: String
)

@JsonClass(generateAdapter = true)
data class ProvisionResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class PingResponse(
    @Json(name = "status") val status: String,
    @Json(name = "mode") val mode: String
)

@JsonClass(generateAdapter = true)
data class OtaJobRequest(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "firmware_url") val firmwareUrl: String,
    @Json(name = "firmware_ver") val firmwareVer: String,
    @Json(name = "firmware_sha256") val firmwareSha256: String,
    @Json(name = "firmware_sig") val firmwareSig: String,
    @Json(name = "firmware_size") val firmwareSize: Long
)

@JsonClass(generateAdapter = true)
data class OtaJobResponse(
    @Json(name = "job_id") val jobId: String,
    @Json(name = "status") val status: String,
    @Json(name = "chunks_sent") val chunksSent: Int = 0,
    @Json(name = "chunks_total") val chunksTotal: Int = 100
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class RegisterRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class SessionInfo(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String? = null
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    @Json(name = "session") val session: SessionInfo? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class RegisterResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class RefreshRequest(
    @Json(name = "refresh_token") val refreshToken: String
)

@JsonClass(generateAdapter = true)
data class RefreshResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String? = null
)

@JsonClass(generateAdapter = true)
data class CloudDeviceRegisterRequest(
    @Json(name = "device_name") val deviceName: String,
    @Json(name = "device_type") val deviceType: String,
    @Json(name = "room") val room: String
)

@JsonClass(generateAdapter = true)
data class CloudDeviceRegisterResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "ssl_cert") val sslCert: String? = null,
    @Json(name = "error") val error: String? = null
)

