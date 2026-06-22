package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY deviceId ASC")
    fun getAllDevicesFlow(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDeviceById(deviceId: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId LIMIT 1")
    fun getDeviceByIdFlow(deviceId: String): Flow<DeviceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDevice(device: DeviceEntity)

    @Query("UPDATE devices SET relayState = :state WHERE deviceId = :deviceId")
    suspend fun updateRelayState(deviceId: String, state: Boolean)

    @Query("UPDATE devices SET isOnline = :isOnline, lastSeen = :lastSeen WHERE deviceId = :deviceId")
    suspend fun updateOnlineStatus(deviceId: String, isOnline: Boolean, lastSeen: String)

    @Query("UPDATE devices SET rssi = :rssi, rttMs = :rttMs WHERE deviceId = :deviceId")
    suspend fun updateNetworkMetrics(deviceId: String, rssi: Int?, rttMs: Int?)

    @Query("DELETE FROM devices WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    @Query("DELETE FROM devices")
    suspend fun clearAllDevices()
}

@Dao
interface HealthDao {
    @Query("SELECT * FROM health_telemetry WHERE deviceId = :deviceId ORDER BY recordedAt DESC")
    fun getHealthHistoryFlow(deviceId: String): Flow<List<HealthEntity>>

    @Query("SELECT * FROM health_telemetry WHERE deviceId = :deviceId ORDER BY recordedAt DESC LIMIT 1")
    fun getLatestHealthFlow(deviceId: String): Flow<HealthEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHealthRecord(health: HealthEntity)

    @Query("DELETE FROM health_telemetry WHERE deviceId = :deviceId")
    suspend fun clearHistory(deviceId: String)

    @Query("DELETE FROM health_telemetry")
    suspend fun clearAllTelemetry()
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM device_groups ORDER BY groupName ASC")
    fun getAllGroupsFlow(): Flow<List<GroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateGroup(group: GroupEntity)

    @Query("UPDATE device_groups SET isActive = :isActive WHERE groupId = :groupId")
    suspend fun updateGroupState(groupId: Long, isActive: Boolean)

    @Query("DELETE FROM device_groups WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: Long)
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automation_rules ORDER BY ruleName ASC")
    fun getAllRulesFlow(): Flow<List<AutomationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRule(rule: AutomationEntity)

    @Query("UPDATE automation_rules SET isEnabled = :isEnabled WHERE ruleId = :ruleId")
    suspend fun updateRuleEnabled(ruleId: String, isEnabled: Boolean)

    @Query("DELETE FROM automation_rules WHERE ruleId = :ruleId")
    suspend fun deleteRule(ruleId: String)
}
