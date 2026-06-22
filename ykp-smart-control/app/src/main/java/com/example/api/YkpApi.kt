package com.example.api

import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class DeviceResponse(
    @Json(name = "id") val id: String? = null,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "device_name") val deviceName: String,
    @Json(name = "device_type") val deviceType: String,
    @Json(name = "firmware_ver") val firmwareVer: String,
    @Json(name = "is_online") val isOnline: Boolean,
    @Json(name = "ip_address") val ipAddress: String? = null,
    @Json(name = "last_seen") val lastSeen: String? = null,
    @Json(name = "capabilities") val capabilities: Int = 1,
    @Json(name = "location") val location: String? = null,
    @Json(name = "relay_state") val relayState: Boolean = false
)

interface YkpRouterApi {
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): RegisterResponse

    @POST("api/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): RefreshResponse

    @GET("api/devices")
    suspend fun getDevices(): List<DeviceResponse>

    @POST("api/devices")
    suspend fun registerDevice(@Body request: CloudDeviceRegisterRequest): CloudDeviceRegisterResponse


    @GET("api/devices/{deviceId}")
    suspend fun getDevice(@Path("deviceId") deviceId: String): DeviceResponse

    @POST("api/devices/{deviceId}/command")
    suspend fun sendCommand(
        @Path("deviceId") deviceId: String,
        @Body request: CommandRequest
    ): CommandResponse

    @GET("api/health/{deviceId}")
    suspend fun getHealth(@Path("deviceId") deviceId: String): HealthResponse

    @GET("api/health/{deviceId}/history")
    suspend fun getHealthHistory(
        @Path("deviceId") deviceId: String,
        @Query("hours") hours: Int
    ): List<HealthResponse>

    @POST("api/ota")
    suspend fun createOtaJob(@Body request: OtaJobRequest): OtaJobResponse

    @POST("api/ota/{jobId}/start")
    suspend fun startOtaJob(@Path("jobId") jobId: String): OtaJobResponse

    @GET("api/ssl-cert")
    suspend fun getSslCert(): okhttp3.ResponseBody
}

interface SoftApApi {
    @GET("ping")
    suspend fun ping(): PingResponse

    @POST("provision")
    suspend fun provision(@Body payload: ProvisionPayload): ProvisionResponse
}

object YkpApiClient {
    private var sYkpService: YkpRouterApi? = null
    private var sSoftApService: SoftApApi? = null

    var sessionToken: String? = null
        set(value) {
            field = value
            sYkpService = null // reset cached service to re-init with new URL/client
        }

    var refreshToken: String? = null
        set(value) {
            field = value
            sYkpService = null
        }

    suspend fun refreshSession(): Boolean {
        val rToken = refreshToken ?: return false
        return try {
            val api = getRouterApi()
            val resp = api.refresh(RefreshRequest(rToken))
            sessionToken = resp.accessToken
            if (!resp.refreshToken.isNullOrEmpty()) {
                refreshToken = resp.refreshToken
            }
            Log.d("YkpApiClient", "Session dynamically refreshed successfully!")
            true
        } catch (e: Exception) {
            Log.e("YkpApiClient", "Failed to refresh session dynamically: ${e.message}")
            false
        }
    }

    suspend fun fetchSslCertificate(): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val api = getRouterApi()
                val responseBody = api.getSslCert()
                val cert = responseBody.string()
                Log.d("YkpApiClient", "fetchSslCertificate retrieved certificate size: ${cert.length}")
                cert
            } catch (e: Exception) {
                Log.e("YkpApiClient", "fetchSslCertificate failed to fetch remote SSL certificate: ${e.message}", e)
                throw e
            }
        }
    }

    // Base cloud server URLs matching config default
    var cloudServerUrl = "https://iot-yk.onrender.com"
        set(value) {
            field = value
            sYkpService = null // reset cached service to re-init with new URL
        }

    // Default SoftAP ESP32 Address
    var softApUrl = "http://192.168.4.1"
        set(value) {
            field = value
            sSoftApService = null // reset cached service to re-init with new URL
        }

    fun getRouterApi(): YkpRouterApi {
        return sYkpService ?: synchronized(this) {
            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val token = sessionToken
                    var requestBuilder = original.newBuilder()
                    if (!token.isNullOrEmpty()) {
                        requestBuilder.header("Authorization", "Bearer $token")
                    }
                    var response = chain.proceed(requestBuilder.build())
                    
                    // Catch HTTP 401 Unauthorized errors silently, refresh, and retry command
                    if (response.code == 401) {
                        val rToken = refreshToken
                        if (!rToken.isNullOrEmpty()) {
                            response.close() // Close original response first
                            
                            val refreshUrl = if (cloudServerUrl.endsWith("/")) "${cloudServerUrl}api/auth/refresh" else "$cloudServerUrl/api/auth/refresh"
                            val refreshMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                            val refreshJson = "{\"refresh_token\":\"$rToken\"}"
                            val refreshBody = okhttp3.RequestBody.create(refreshMediaType, refreshJson)
                            val refreshReq = okhttp3.Request.Builder()
                                .url(refreshUrl)
                                .post(refreshBody)
                                .build()
                            
                            val okClientUnauth = okhttp3.OkHttpClient()
                            try {
                                val refreshCall = okClientUnauth.newCall(refreshReq).execute()
                                if (refreshCall.isSuccessful) {
                                    val refreshBodyStr = refreshCall.body?.string() ?: ""
                                    val jsonObj = org.json.JSONObject(refreshBodyStr)
                                    val newAccessToken = jsonObj.optString("access_token")
                                    val newRefreshToken = jsonObj.optString("refresh_token")
                                    if (newAccessToken.isNotEmpty()) {
                                        sessionToken = newAccessToken
                                        if (newRefreshToken.isNotEmpty()) {
                                            refreshToken = newRefreshToken
                                        }
                                        Log.d("YkpApiClient", "Silent interceptor successfully refreshed token!")
                                        
                                        // Retry the original request with the new access token
                                        requestBuilder = original.newBuilder()
                                        requestBuilder.header("Authorization", "Bearer $newAccessToken")
                                        response = chain.proceed(requestBuilder.build())
                                        return@addInterceptor response
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("YkpApiClient", "Silent interceptor failed to refresh token", e)
                            }
                        }
                    }
                    response
                }
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(if (cloudServerUrl.endsWith("/")) cloudServerUrl else "$cloudServerUrl/")
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
            val service = retrofit.create(YkpRouterApi::class.java)
            sYkpService = service
            service
        }
    }

    fun getSoftApApi(): SoftApApi {
        return sSoftApService ?: synchronized(this) {
            val retrofit = Retrofit.Builder()
                .baseUrl(if (softApUrl.endsWith("/")) softApUrl else "$softApUrl/")
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
            val service = retrofit.create(SoftApApi::class.java)
            sSoftApService = service
            service
        }
    }
}
