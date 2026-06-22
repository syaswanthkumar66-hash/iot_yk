package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String, // e.g., "SW001" (assumed custom ID as primary key)
    val deviceName: String,
    val deviceType: String, // "switch", "sensor", "motor", "gateway"
    val firmwareVer: String,
    val isOnline: Boolean,
    val ipAddress: String?,
    val lastSeen: String?,
    val capabilities: Int,
    val location: String?,
    val relayState: Boolean, // State of relay (true=ON, false=OFF)
    val lastPacketId: Int = 0,
    val rssi: Int? = null,
    val rttMs: Int? = null
)

@Entity(tableName = "health_telemetry")
data class HealthEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val cpuUsage: Double,
    val freeHeap: Long,
    val minHeap: Long,
    val rssi: Int,
    val temperature: Double,
    val battery: Int = 100,
    val packetLoss: Double = 0.0,
    val rttMs: Int = 10,
    val uptimeSec: Long,
    val restartCount: Int,
    val recordedAt: String // ISO timestamp
)

@Entity(tableName = "device_groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val groupId: Long = 0,
    val groupName: String,
    val members: String, // Comma-separated device IDs
    val isActive: Boolean
)

@Entity(tableName = "automation_rules")
data class AutomationEntity(
    @PrimaryKey val ruleId: String,
    val ruleName: String,
    val triggerDevice: String,
    val triggerService: Int,
    val triggerAction: Int,
    val targetDevice: String,
    val targetService: Int,
    val targetAction: Int,
    val isEnabled: Boolean
)
