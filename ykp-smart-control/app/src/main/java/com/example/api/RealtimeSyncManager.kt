package com.example.api

import android.util.Log
import com.example.data.YkpRepository
import okhttp3.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RealtimeSyncManager(
    private val repository: YkpRepository,
    private val wsUrlProvider: () -> String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Infinite read timeout for persistent stream
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var activeCall: Call? = null
    private var isConnected = false
    private var keepAlive = true
    private var reconnectThread: Thread? = null

    fun start() {
        Log.d("RealtimeSyncManager", "Starting Realtime Live Stream SSE...")
        keepAlive = true
        connect()
    }

    fun stop() {
        Log.d("RealtimeSyncManager", "Stopping Realtime Live Stream SSE...")
        keepAlive = false
        disconnect()
    }

    @Synchronized
    private fun connect() {
        if (isConnected || !keepAlive) return

        val baseUrl = YkpApiClient.cloudServerUrl
        val token = YkpApiClient.sessionToken

        if (token.isNullOrEmpty()) {
            Log.d("RealtimeSyncManager", "Cannot connect to stream: No active auth session token.")
            return
        }

        val normalizedUrl = if (baseUrl.endsWith("/")) {
            "${baseUrl}api/events"
        } else {
            "${baseUrl}/api/events"
        }

        Log.d("RealtimeSyncManager", "Connecting to SSE stream endpoint: $normalizedUrl")
        val request = Request.Builder()
            .url(normalizedUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "text/event-stream")
            .build()

        val call = client.newCall(request)
        activeCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!keepAlive) return
                isConnected = false
                Log.e("RealtimeSyncManager", "SSE Stream connection failed: ${e.message}", e)
                triggerReconnect()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    isConnected = false
                    val responseCode = response.code
                    Log.e("RealtimeSyncManager", "SSE Stream rejected by server (Code $responseCode)")
                    response.close()
                    
                    if (responseCode == 401) {
                        Log.d("RealtimeSyncManager", "SSE got 401 Unauthorized, performing silent token refresh & stream reconnection...")
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                val success = YkpApiClient.refreshSession()
                                if (success) {
                                    Log.d("RealtimeSyncManager", "Dynamic SSE JWT renewal succeeded.")
                                } else {
                                    Log.e("RealtimeSyncManager", "Dynamic SSE JWT renewal failed.")
                                }
                            } catch (e: Exception) {
                                Log.e("RealtimeSyncManager", "Exception renewing token", e)
                            } finally {
                                triggerReconnect()
                            }
                        }
                    } else {
                        triggerReconnect()
                    }
                    return
                }

                isConnected = true
                Log.d("RealtimeSyncManager", "SSE Stream successfully established!")

                val body = response.body
                if (body == null) {
                    isConnected = false
                    Log.e("RealtimeSyncManager", "SSE Stream response has empty body.")
                    response.close()
                    triggerReconnect()
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
                    var line: String? = null
                    var currentEvent: String? = null
                    val dataAccumulator = StringBuilder()

                    while (keepAlive && reader.readLine().also { line = it } != null) {
                        val currentLine = line!!
                        if (currentLine.isEmpty()) {
                            // Empty line triggers event dispatch
                            val data = dataAccumulator.toString().trim()
                            if (data.isNotEmpty()) {
                                handleSseEvent(currentEvent ?: "message", data)
                            }
                            currentEvent = null
                            dataAccumulator.setLength(0)
                        } else if (currentLine.startsWith("event:")) {
                            currentEvent = currentLine.substring(6).trim()
                        } else if (currentLine.startsWith("data:")) {
                            if (dataAccumulator.isNotEmpty()) {
                                dataAccumulator.append("\n")
                            }
                            dataAccumulator.append(currentLine.substring(5).trim())
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RealtimeSyncManager", "Exception readLine from SSE Stream body source: ${e.message}")
                } finally {
                    isConnected = false
                    response.close()
                    triggerReconnect()
                }
            }
        })
    }

    private fun disconnect() {
        activeCall?.cancel()
        activeCall = null
        isConnected = false
        reconnectThread?.interrupt()
        reconnectThread = null
    }

    @Synchronized
    private fun triggerReconnect() {
        if (!keepAlive) return
        reconnectThread?.interrupt()
        reconnectThread = Thread {
            try {
                Thread.sleep(5000)
                Log.d("RealtimeSyncManager", "Attempting SSE reconnection...")
                connect()
            } catch (e: InterruptedException) {
                // Suppressed
            }
        }
        reconnectThread?.start()
    }

    private fun handleSseEvent(event: String, data: String) {
        Log.d("RealtimeSyncManager", "SSE Emitted Event: [event=$event] payload=[$data]")
        try {
            val payload = JSONObject(data)
            val type = payload.optString("type")
            when (type) {
                "DEVICE_PRESENCE_CHANGED" -> {
                    val deviceId = payload.optString("deviceId")
                    val status = payload.optString("status")
                    val isOnline = if (status.isNotEmpty()) {
                        status.equals("ONLINE", ignoreCase = true)
                    } else {
                        payload.optBoolean("is_online", true)
                    }
                    if (deviceId.isNotEmpty()) {
                        GlobalScope.launch(Dispatchers.IO) {
                            repository.updateDevicePresence(deviceId, isOnline)
                        }
                    }
                }
                "DEVICE_STATE_CHANGED", "STATE_CHANGED", "DEVICE_TELEMETRY", "TELEMETRY" -> {
                    val deviceId = payload.optString("deviceId")
                    val relayState = payload.optBoolean("relay_state", false) || payload.optBoolean("state", false)
                    if (deviceId.isNotEmpty()) {
                        GlobalScope.launch(Dispatchers.IO) {
                            repository.updateDeviceLocalState(deviceId, relayState)
                        }
                    }
                    
                    // Parse network metrics
                    val sensorData = payload.optJSONObject("sensor_data")
                    var rttVal: Int? = null
                    var rssiVal: Int? = null
                    
                    if (sensorData != null) {
                        if (sensorData.has("rtt_ms")) {
                            rttVal = sensorData.optInt("rtt_ms")
                        }
                        if (sensorData.has("rssi")) {
                            rssiVal = sensorData.optInt("rssi")
                        }
                    } else {
                        if (payload.has("rtt_ms")) {
                            rttVal = payload.optInt("rtt_ms")
                        }
                        if (payload.has("rssi")) {
                            rssiVal = payload.optInt("rssi")
                        }
                    }
                    
                    if (deviceId.isNotEmpty() && (rttVal != null || rssiVal != null)) {
                        GlobalScope.launch(Dispatchers.IO) {
                            repository.updateNetworkMetrics(deviceId, rssiVal, rttVal)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RealtimeSyncManager", "Exception parsing SSE event JSON structure", e)
        }
    }
}
