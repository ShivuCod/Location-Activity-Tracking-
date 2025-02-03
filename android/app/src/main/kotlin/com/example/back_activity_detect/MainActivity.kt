package com.example.back_activity_detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.test"
    private val LOCATION_CHANNEL = "com.test/location_updates"
    private val ACTIVITY_CHANNEL = "com.test/activity_updates"
    private val TAG = "MainActivity"

    private var locationEventSink: EventChannel.EventSink? = null
    private var activityEventSink: EventChannel.EventSink? = null
    
    // Keep track of latest data
    private var lastKnownLocation: HashMap<String, Any>? = null
    private var lastKnownActivity: HashMap<String, Any>? = null

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "location_update") {
                val locationData = HashMap<String, Any>().apply {
                    put("latitude", intent.getDoubleExtra("latitude", 0.0))
                    put("longitude", intent.getDoubleExtra("longitude", 0.0))
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
                                    put("activityType", when (event.activityType) {
                                        DetectedActivity.STILL -> "STILL"
                                        DetectedActivity.WALKING -> "WALKING"
                                        DetectedActivity.RUNNING -> "RUNNING"
                                        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                                        else -> "UNKNOWN"
                                    })
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
                        val detectedActivity = intent.getParcelableExtra<DetectedActivity>("activity_data")
                        detectedActivity?.let {
                            val activityData = HashMap<String, Any>().apply {
                                put("activityType", when (it.type) {
                                    DetectedActivity.STILL -> "STILL"
                                    DetectedActivity.WALKING -> "WALKING"
                                    DetectedActivity.RUNNING -> "RUNNING"
                                    DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                                    else -> "UNKNOWN"
                                })
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

    /**
     * Process location and activity data updates.
     * This function is called whenever new location or activity data is received.
     * You can add your custom logic here to handle the updates.
     */
    private fun processLocationAndActivityData(
        locationData: HashMap<String, Any>?,
        activityData: HashMap<String, Any>?
    ) {
        // Update last known data
        locationData?.let { lastKnownLocation = it }
        activityData?.let { lastKnownActivity = it }

        // Example: Log combined data
        val currentLocation = lastKnownLocation
        val currentActivity = lastKnownActivity
        
        if (currentLocation != null && currentActivity != null) {
            Log.d(TAG, """
                Combined Data:
                Location: (${currentLocation["latitude"]}, ${currentLocation["longitude"]})
                Speed: ${currentLocation["speed"]} m/s
                Activity: ${currentActivity["activityType"]}
                ${if (currentActivity.containsKey("confidence")) "Confidence: ${currentActivity["confidence"]}%" else ""}
                Time: ${currentLocation["time"]}
            """.trimIndent())
            
            // Add your custom logic here
            // For example:
            // 1. Save to local database
            // 2. Make API calls
            // 3. Trigger notifications based on activity and location
            // 4. Analyze movement patterns
            // 5. Calculate statistics
            
            when (currentActivity["activityType"]) {
                "STILL" -> {
                    // Handle still activity
                    // Example: Check if user has been stationary for too long
                }
                "WALKING" -> {
                    // Handle walking activity
                    // Example: Calculate walking distance and speed
                }
                "RUNNING" -> {
                    // Handle running activity
                    // Example: Track exercise metrics
                }
                "IN_VEHICLE" -> {
                    // Handle vehicle activity
                    // Example: Calculate travel distance and average speed
                }
            }
        }
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
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
            registerReceiver(locationReceiver, locationFilter)
            registerReceiver(activityReceiver, activityFilter)
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
}
