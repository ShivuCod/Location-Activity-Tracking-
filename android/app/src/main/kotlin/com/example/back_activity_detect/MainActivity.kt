package com.example.back_activity_detect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.test"
    private val LOCATION_CHANNEL = "com.test/location_updates"
    private val ACTIVITY_CHANNEL = "com.test/activity_updates"
    private val TAG = "MainActivity"
    private lateinit var sharedPreferences: SharedPreferences
    private var webSocket: WebSocket? = null
    private var isWebSocketConnected = false
    private val WEBSOCKET_RECONNECT_DELAY = 5000L // 5 seconds
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    private val activityLocations = mutableListOf<Map<String, Any>>()
    private val API_KEY = "AIzaSyD9Kyz9si6gdVi7evO61r0JFYUoBYB91lo"
    private val ADD_ACTIVITY_URL = "/api/activities/add" 
    private var lastActivityTime: Date? = null
    private var lastActivity: String = "Stop"
    private var locationEventSink: EventChannel.EventSink? = null
    private var activityEventSink: EventChannel.EventSink? = null
    
    // Initialize OkHttpClient
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // Keep track of latest data
    private var lastKnownLocation: HashMap<String, Any>? = null
    private var lastKnownActivity: HashMap<String, Any>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        connectWebSocket()
    }

    private fun scheduleWebSocketReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        
        reconnectRunnable = Runnable {
            if (!isWebSocketConnected) {
                connectWebSocket()
            }
        }.also {
            handler.postDelayed(it, WEBSOCKET_RECONNECT_DELAY)
        }
    }

    private fun connectWebSocket() {
        if (isWebSocketConnected) {
            Log.d(TAG, "WebSocket already connected")
            return
        }

        val orgId = sharedPreferences.getString("orgId", null)
        val accessToken = sharedPreferences.getString("accessToken", null)
        
        if (orgId == null || accessToken == null) {
            Log.e(TAG, "Cannot connect to WebSocket: missing orgId or accessToken")
            return
        }

        val serverUrl = "wss://api.geo.humanec.ai/ws/tracklocation/$orgId?token=$accessToken"

        val wsClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)  // Keep connection alive
            .build()

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isWebSocketConnected = true
                reconnectRunnable?.let { handler.removeCallbacks(it) }
                Log.d(TAG, "WebSocket Connected to $serverUrl")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWebSocketConnected = false
                Log.e(TAG, "WebSocket Error: ${t.message}")
                scheduleWebSocketReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWebSocketConnected = false
                Log.d(TAG, "WebSocket Closed with code: $code, reason: $reason")
                scheduleWebSocketReconnect()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WebSocket message received: $text")
            }
        })
    }

    private fun closeWebSocket() {
        webSocket?.close(1000, "Activity finished")
        webSocket = null
        isWebSocketConnected = false
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    override fun onResume() {
        super.onResume()
        if (!isWebSocketConnected) {
            connectWebSocket()
        }
    }

    override fun onPause() {
        super.onPause()
        closeWebSocket()
    }

    private fun sendToServer(activityData: JSONObject) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = activityData.toString().toRequestBody(mediaType)
        val host = sharedPreferences.getString("host", null)
        var accessToken =sharedPreferences.getString("accessToken", null)

        if (host == null) {
            Log.e(TAG, "Cannot send to server: missing host")
            return
        }


        val url = "$host$ADD_ACTIVITY_URL"
        Log.d(TAG, "Sending request to: $url")
        Log.d(TAG, "Request body: $activityData")

        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to send activity: ${e.message}")
                Log.e(TAG, "Error stack trace:", e)
                // Try to reconnect WebSocket on failure
                if (e is java.net.ConnectException || e is java.net.SocketTimeoutException) {
                    isWebSocketConnected = false
                    scheduleWebSocketReconnect()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { 
                    val responseBody = it.body?.string()
                    Log.d(TAG, "Response code: ${it.code}")
                    Log.d(TAG, "Response body: $responseBody")
                    
                    if (it.isSuccessful) {
                        Log.d(TAG, "Activity sent successfully")
                    } else {
                        Log.e(TAG, "Failed to send activity. Response code: ${it.code}")
                        Log.e(TAG, "Error response: $responseBody")
                        // Try to reconnect WebSocket on API error
                        if (it.code == 401 || it.code == 403) {
                            isWebSocketConnected = false
                            scheduleWebSocketReconnect()
                        }else{

                        }
                    }
                }
            }
        })
    }

    private fun getBatteryLevel(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun sendLocationUpdate(currentLocation: Map<String, Any>, activity: String) {
        print("Acitu----------$activity")
        if (!isWebSocketConnected) {
            Log.d(TAG, "WebSocket not connected, attempting to reconnect...")
            connectWebSocket()
            return
        }

        webSocket?.let { socket ->
            try {
                val orgId = sharedPreferences.getString("orgId", null)
                val empId = sharedPreferences.getString("empId", null)
                val empName = sharedPreferences.getString("name", null)
                val img = sharedPreferences.getString("img", null)
                val battery = getBatteryLevel(this)

                if (orgId == null || empId == null) {
                    Log.e(TAG, "Missing required user data (orgId or empId)")
                    return@let
                }

                val locationJson = JSONObject().apply {
                    put("empId", empId)
                    put("emp", empName ?: "")
                    put("battery", battery)
                    put("image", img ?: "")
                    put("message", activity)
                    put("duration", 0)
                    put("location", JSONObject().apply {
                        put("lat", String.format("%.6f", currentLocation["latitude"]).toDouble())
                        put("long", String.format("%.6f", currentLocation["longitude"]).toDouble())
                        put("bearing", currentLocation["bearing"] ?: 0.0)
                    })
                }

                val success = socket.send(locationJson.toString())
                if (!success) {
                    Log.e(TAG, "Failed to send location update through WebSocket")
                    isWebSocketConnected = false
                    scheduleWebSocketReconnect()
                } else {
                    Log.d(TAG, "WebSocket message sent: $locationJson")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending location update: ${e.message}")
                isWebSocketConnected = false
                scheduleWebSocketReconnect()
            }
        } ?: Log.e(TAG, "WebSocket is not connected")
    }

    private fun calculateDistance(locations: List<Map<String, Any>>): Double {
        var totalDistance = 0.0
        if (locations.size < 2) return totalDistance
        
        for (i in 0 until locations.size - 1) {
            val lat1 = locations[i]["latitude"].toString().toDouble()
            val lon1 = locations[i]["longitude"].toString().toDouble()
            val lat2 = locations[i + 1]["latitude"].toString().toDouble()
            val lon2 = locations[i + 1]["longitude"].toString().toDouble()
            
            totalDistance += calculateHaversineDistance(lat1, lon1, lat2, lon2)
        }
        return totalDistance
    }

    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth's radius in kilometers
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun calculateDuration(startTime: Long, endTime: Long): Double {
        return ((endTime - startTime) / 1000.0 / 60.0) // Duration in minutes
    }

    private fun processLocationAndActivityData(
        locationData: HashMap<String, Any>?,
        activityData: HashMap<String, Any>?
    ) {
        locationData?.let { lastKnownLocation = it }
        activityData?.let { lastKnownActivity = it }

        val currentLocation = lastKnownLocation
        val currentActivity = lastKnownActivity
       
        currentLocation?.let { activityLocations.add(it) }
        
        if (currentLocation != null && currentActivity != null) {
            if (lastActivity != currentActivity["activityType"]) {
                lastActivityTime = Date()
                lastActivity = currentActivity["activityType"]?.toString() ?: lastActivity
                Log.d("PARAMS---->", "BODY-----{activity- $lastActivity currentActivity ${currentActivity["activityType"]} locations -> ${activityLocations}")

                val currentTime = System.currentTimeMillis()
                
                lifecycleScope.launch(Dispatchers.IO) {
                    val address = getAddress(currentLocation["latitude"].toString().toDouble(), 
                                          currentLocation["longitude"].toString().toDouble())
                    
                    // Calculate distance and duration
                    val distance = calculateDistance(activityLocations)
                    val duration = if (lastActivityTime != null) {
                        calculateDuration(lastActivityTime!!.time, currentTime)
                    } else 0.0

                    // Create activity JSON
                    val activityJson = JSONObject().apply {
                        put("organization", sharedPreferences.getString("orgId", ""))
                        put("employee", sharedPreferences.getString("empId", ""))
                        put("activity", lastActivity)
                        put("time", formatTime(currentTime))
                        put("date", formatDate(currentTime))
                        put("address", address)
                        put("description", "A Location Tracking ${if (activityLocations.isEmpty()) "Started" else "Updated"}")
                        put("distance", String.format("%.2f", distance))
                        put("duration", String.format("%.2f", duration))
                        put("path", JSONArray().apply {
                            activityLocations.forEach { location ->
                                put(JSONObject().apply {
                                    put("latitude", String.format("%.6f", location["latitude"]).toDouble())
                                    put("longitude", String.format("%.6f", location["longitude"]).toDouble())
                                    put("bearing", location["bearing"] ?: 0.0)
                                })
                            }
                        })
                    }

                    // Send to server
                    // val accessToken = sharedPreferences.getString("access_token", null)
                    // if (accessToken != null) {
                        Log.e("Going for Add Activity", "Going for Add Activity")
                        sendToServer(activityJson)
                        activityLocations.clear() // Clear locations after sending
                    // }else{
                    //     print("Access Token Expire")
                    // }
                }
            } else {
                // Send location update via WebSocket
                sendLocationUpdate(currentLocation, lastActivity)
                Log.d("PARAMS---->", " SAME --BODY-----{activity- $lastActivity currentActivity ${currentActivity["activityType"]} locations -> ${activityLocations}")
            }
            
            Log.d(TAG, """
                Combined Data for Org: ${sharedPreferences.getString("orgId", "")}, Emp: ${sharedPreferences.getString("empId", "")}
                Location: (${currentLocation["latitude"]}, ${currentLocation["longitude"]})
                Speed: ${currentLocation["speed"]} m/s
                Activity: ${currentActivity["activityType"]}
                ${if (currentActivity.containsKey("confidence")) "Confidence: ${currentActivity["confidence"]}%" else ""}
                Time: ${System.currentTimeMillis()}
            """.trimIndent())
        }
    }

    private fun getActivityName(activityType: Int): String {
        return when (activityType) {
            DetectedActivity.STILL -> "Stop"
            DetectedActivity.WALKING -> "Walk"
            DetectedActivity.IN_VEHICLE -> "Drive"
            else -> "Unknown"
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "location_update") {
                val locationData = HashMap<String, Any>().apply {
                    put("latitude", String.format("%.6f", intent.getDoubleExtra("latitude", 0.0)).toDouble())
                    put("longitude", String.format("%.6f", intent.getDoubleExtra("longitude", 0.0)).toDouble())
                    put("accuracy", intent.getFloatExtra("accuracy", 0f))
                    put("speed", intent.getFloatExtra("speed", 0f))
                    put("time", intent.getLongExtra("time", 0L))
                }

                // Process the data
                processLocationAndActivityData(locationData, null)
                
                // Send to Flutter
                locationEventSink?.success(locationData)
            }
        }
    }

    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "activity_transition_update" -> {
                    if (ActivityTransitionResult.hasResult(intent)) {
                        val result = ActivityTransitionResult.extractResult(intent)
                        result?.let { 
                            for (event in it.transitionEvents) {
                                val activityData = HashMap<String, Any>().apply {
                                    put("activityType", getActivityName(event.activityType))
                                    put("time", event.elapsedRealTimeNanos / 1_000_000)
                                    put("isTransition", true)
                                }
                                
                                // Process the data
                                processLocationAndActivityData(null, activityData)
                                
                                // Send to Flutter
                                activityEventSink?.success(activityData)
                            }
                        }
                    }
                }
                "activity_update" -> {
                    if (intent.hasExtra("activity_data")) {
                        val detectedActivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra("activity_data", DetectedActivity::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra("activity_data")
                        }
                        
                        detectedActivity?.let {
                            val activityData = HashMap<String, Any>().apply {
                                put("activityType", getActivityName(it.type))
                                put("confidence", it.confidence)
                                put("time", System.currentTimeMillis())
                                put("isTransition", false)
                            }
                            
                            // Process the data
                            processLocationAndActivityData(null, activityData)
                            
                            // Send to Flutter
                            activityEventSink?.success(activityData)
                        }
                    }
                }
            }
        }
    }

    private suspend fun getAddress(latitude: Double, longitude: Double): String = withContext(Dispatchers.IO) {
        val url = "https://maps.googleapis.com/maps/api/geocode/json?latlng=$latitude,$longitude&key=$API_KEY"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Unknown Location"
                }
                
                val jsonData = JSONObject(response.body?.string() ?: "")
                if (jsonData.getString("status") == "OK") {
                    val results = jsonData.getJSONArray("results")
                    for (i in 0 until results.length()) {
                        val place = results.getJSONObject(i)
                        if (!place.getJSONArray("types").toString().contains("plus_code")) {
                            return@withContext place.getString("formatted_address")
                        }
                    }
                }
                return@withContext "Unknown Location"
            }
        } catch (e: Exception) {
            Log.e("ActivityService", "Error getting address: ${e.message}")
            return@withContext "Unknown Location"
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "userData" -> {
                    try {
                        val data = call.arguments as? Map<*, *>
                        if (data != null) {
                            with(sharedPreferences.edit()) {
                                putString("orgId", data["orgId"]?.toString())
                                putString("empId", data["empId"]?.toString())
                                putString("accessToken", data["accessToken"]?.toString())
                                putString("refreshToken", data["refreshToken"]?.toString())
                                putString("img", data["img"]?.toString())
                                putString("name", data["name"]?.toString())
                                putString("host", data["host"]?.toString())
                                apply()
                            }
                            Log.d(TAG, "User data saved to SharedPreferences: $data")
                            result.success(true)
                        } else {
                            result.error("INVALID_ARGUMENTS", "Arguments were null or invalid", null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving user data", e)
                        result.error("SAVE_ERROR", e.message, null)
                    }
                }
                "startLocationService" -> {
                    startLocationService()
                    result.success(null)
                }
                "stopLocationService" -> {
                    stopLocationService()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, LOCATION_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    Log.d(TAG, "Location EventChannel: onListen called")
                    locationEventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    Log.d(TAG, "Location EventChannel: onCancel called")
                    locationEventSink = null
                }
            }
        )

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, ACTIVITY_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    Log.d(TAG, "Activity EventChannel: onListen called")
                    activityEventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    Log.d(TAG, "Activity EventChannel: onCancel called")
                    activityEventSink = null
                }
            }
        )

        // Register broadcast receivers
        val locationFilter = IntentFilter("location_update")
        val activityFilter = IntentFilter().apply {
            addAction("activity_transition_update")
            addAction("activity_update")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationReceiver, locationFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(activityReceiver, activityFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
           if(Build.VERSION.SDK_INT>= Build.VERSION_CODES.O){
               registerReceiver(locationReceiver, locationFilter, Context.RECEIVER_NOT_EXPORTED)
               registerReceiver(activityReceiver, activityFilter, Context.RECEIVER_NOT_EXPORTED)
           }else{
               registerReceiver(locationReceiver, locationFilter, Context.RECEIVER_EXPORTED)
               registerReceiver(activityReceiver, activityFilter, Context.RECEIVER_EXPORTED)
           }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(locationReceiver)
            unregisterReceiver(activityReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
    }

    private fun startLocationService() {
        Log.d(TAG, "Starting location service")
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
            Log.d(TAG, "Starting location service in foreground")
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopLocationService() {
        Log.d(TAG, "Stopping location service")
        val serviceIntent = Intent(this, LocationService::class.java)
        stopService(serviceIntent)
    }

    private fun checkPermissions(): Boolean {
        val hasLocationPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasActivityRecognitionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasLocationPermission || !hasActivityRecognitionPermission) {
            // Send outage activity when permissions are denied
            val outageData = JSONObject().apply {
                put("activity", "Outage Activity")
                put("timestamp", System.currentTimeMillis())
                put("reason", "Permission Denied")
            }
            val accessToken = sharedPreferences.getString("access_token", null)
            if (accessToken != null) {
                sendToServer(outageData)
            }
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                // Send outage activity when permissions are denied
                val outageData = JSONObject().apply {
                    put("activity", "Outage")
                    put("timestamp", System.currentTimeMillis())
                    put("reason", "Permission Denied")
                }
                val accessToken = sharedPreferences.getString("accessToken", null)
                if (accessToken != null) {
                    sendToServer(outageData)
                }
            }
        }
    }
}
