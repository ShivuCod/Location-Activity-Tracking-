package com.example.back_activity_detect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocationService : Service() {
    private var locationManager: LocationManager? = null
    private val CHANNEL_ID = "LocationServiceChannel"
    private val NOTIFICATION_ID = 1
    private val TAG = "LocationService"
    private var activityRecognitionClient: ActivityRecognitionClient? = null
    private var activityTransitionPendingIntent: PendingIntent? = null
    private var isActivityRecognitionSetup = false
    private lateinit var sharedPreferences: SharedPreferences
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.location.PROVIDERS_CHANGED" ||
                intent?.action == "android.location.MODE_CHANGED") {
                checkAndHandlePermissions()
            }
        }
    }

    private val RESTART_DELAY = 1000L // 1 second delay for restart
    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable {
        startForegroundService()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Starting LocationService")
        sharedPreferences = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        
        // Register for permission changes
        val filter = IntentFilter().apply {
            addAction("android.location.PROVIDERS_CHANGED")
            addAction("android.location.MODE_CHANGED")
        }
        registerReceiver(permissionReceiver, filter)
        
        createNotificationChannel()
        
        // Start both location and activity recognition
        initializeServices()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Schedule a restart
        handler.postDelayed(restartRunnable, RESTART_DELAY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        // Get credentials from intent if available
        intent?.getStringExtra("credentials")?.let { credentialsString ->
            try {
                val credentialsJson = JSONObject(credentialsString)
                // Store credentials in SharedPreferences
                with(sharedPreferences.edit()) {
                    putString("flutter.credentials", credentialsString)
                    apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse credentials", e)
            }
        }

        // Start the service
        startForegroundService()
        
        // If service gets killed, restart it
        return START_STICKY
    }

    private fun checkAndHandlePermissions() {
        val hasLocationPermission = ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasActivityRecognitionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasLocationPermission || !hasActivityRecognitionPermission) {
            // Create outage activity JSON
            val outageData = JSONObject().apply {
                put("activity", "Outage")
                put("time", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                put("date", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
                put("description", if (!hasLocationPermission && !hasActivityRecognitionPermission) {
                    "User Removed Location and Activity Recognition Permissions"
                } else if (!hasLocationPermission) {
                    "User Removed Location Permission"
                } else {
                    "User Removed Activity Recognition Permission"
                })
            }

            // Send to server
            val host = sharedPreferences.getString("host", null)
            val accessToken = sharedPreferences.getString("accessToken", null)
            
            if (host != null && accessToken != null) {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = outageData.toString().toRequestBody(mediaType)
                val url = "$host/api/activities/add"
                
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .build()

                OkHttpClient().newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Failed to send outage activity: ${e.message}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (it.isSuccessful) {
                                Log.d(TAG, "Outage activity sent successfully")
                            } else {
                                Log.e(TAG, "Failed to send outage activity. Response code: ${it.code}")
                            }
                        }
                    }
                })
            }

            // Stop the service
            stopSelf()
        }
    }

    private fun initializeServices() {
        try {
            // First setup activity recognition
            setupActivityRecognition()
            
            // Then start location updates
            startLocationUpdates()
            
            Log.d(TAG, "Successfully initialized both location and activity recognition services")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing services", e)
            stopSelf()
        }
    }

    private fun setupActivityRecognition() {
        try {
            Log.d(TAG, "Setting up activity recognition")
            activityRecognitionClient = ActivityRecognition.getClient(this)
            
            val transitions = listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.WALKING)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            )

            val request = ActivityTransitionRequest(transitions)
            Log.d(TAG, "Created activity transition request")
            
            val intent = Intent("activity_transition_update")
            intent.setPackage(packageName)
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            activityTransitionPendingIntent = PendingIntent.getBroadcast(
                this, 0, intent, flags
            )
            Log.d(TAG, "Created pending intent for activity updates")

            activityRecognitionClient?.requestActivityTransitionUpdates(
                request, activityTransitionPendingIntent!!
            )?.addOnSuccessListener {
                Log.d(TAG, "Successfully registered for activity updates")
                isActivityRecognitionSetup = true
                
                // Also request activity updates (not just transitions)
                requestActivityUpdates()
            }?.addOnFailureListener { e ->
                Log.e(TAG, "Failed to register for activity updates", e)
                isActivityRecognitionSetup = false
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up activity recognition", e)
            isActivityRecognitionSetup = false
            stopSelf()
        }
    }

    private fun requestActivityUpdates() {
        try {
            val intent = Intent("activity_update")
            intent.setPackage(packageName)
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val activityPendingIntent = PendingIntent.getBroadcast(
                this, 1, intent, flags
            )

            activityRecognitionClient?.requestActivityUpdates(
                0, // detectionIntervalMillis (0 for as fast as possible)
                activityPendingIntent
            )?.addOnSuccessListener {
                Log.d(TAG, "Successfully registered for regular activity updates")
            }?.addOnFailureListener { e ->
                Log.e(TAG, "Failed to register for regular activity updates", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting activity updates", e)
        }
    }

    private fun checkPermissions(): Boolean {
        val hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) &&
            hasPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) &&
            hasPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val hasForegroundServicePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hasPermission(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        } else {
            true
        }

        Log.d(TAG, "checkPermissions: Location permissions: $hasLocationPermission")
        Log.d(TAG, "checkPermissions: Foreground service permission: $hasForegroundServicePermission")
        
        return hasLocationPermission && hasForegroundServicePermission
    }

    private fun hasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundService() {
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Tracking")
            .setContentText("Tracking your location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build())
        }
    }

    private fun startLocationUpdates() {
        Log.d(TAG, "startLocationUpdates: Requesting location updates")
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            // Try to get last known location first
            try {
                val lastLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastLocation != null) {
                    Log.d(TAG, "Got last known location: ${lastLocation.latitude}, ${lastLocation.longitude}")
                    locationListener.onLocationChanged(lastLocation)
                } else {
                    Log.d(TAG, "No last known location available")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Error getting last known location", e)
            }

            val minTimeMs = 1000L  // 1 second
            val minDistanceM = 0f   // 2 meters

            var providersEnabled = false

            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                Log.d(TAG, "Requesting GPS updates")
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    locationListener
                )
                providersEnabled = true
            } else {
                Log.w(TAG, "GPS provider is not enabled")
            }
            
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                Log.d(TAG, "Requesting Network updates")
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    locationListener
                )
                providersEnabled = true
            } else {
                Log.w(TAG, "Network provider is not enabled")
            }

            if (!providersEnabled) {
                Log.e(TAG, "No location providers are enabled!")
                // Send a broadcast to inform the UI
                val intent = Intent("location_update").apply {
                    setPackage(packageName)
                    putExtra("error", "No location providers are enabled")
                }
                sendBroadcast(intent)
            }
        } catch (ex: SecurityException) {
            Log.e(TAG, "SecurityException while requesting location updates", ex)
            // Send a broadcast to inform the UI
            val intent = Intent("location_update").apply {
                setPackage(packageName)
                putExtra("error", "Location permission denied")
            }
            sendBroadcast(intent)
            stopSelf()
        }
    }

    private val locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val formattedLat = String.format("%.6f", location.latitude).toDouble()
            val formattedLng = String.format("%.6f", location.longitude).toDouble()
            
            Log.d(TAG, "onLocationChanged: Lat: $formattedLat, Lng: $formattedLng")
            
            val intent = Intent("location_update").apply {
                setPackage(packageName)
                putExtra("latitude", formattedLat)
                putExtra("longitude", formattedLng)
                putExtra("accuracy", location.accuracy)
                putExtra("speed", location.speed)
                putExtra("time", location.time)
            }
            
            try {
                sendBroadcast(intent)
                Log.d(TAG, "onLocationChanged: Broadcast sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "onLocationChanged: Failed to send broadcast", e)
            }
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "onProviderEnabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(TAG, "onProviderDisabled: $provider")
            checkAndHandlePermissions()
        }

        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
            Log.d(TAG, "onStatusChanged: $provider, status: $status")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Location Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Location Service")
        .setContentText("Tracking location and activity...")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Stopping location updates")
        locationManager?.removeUpdates(locationListener)
        
        if (isActivityRecognitionSetup && activityRecognitionClient != null && activityTransitionPendingIntent != null) {
            try {
                activityRecognitionClient?.removeActivityTransitionUpdates(activityTransitionPendingIntent!!)
                    ?.addOnSuccessListener {
                        Log.d(TAG, "Successfully removed activity updates")
                    }
                    ?.addOnFailureListener { e ->
                        Log.e(TAG, "Failed to remove activity updates", e)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing activity recognition updates", e)
            }
        }
        
        try {
            unregisterReceiver(permissionReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        
        // Schedule a restart if not explicitly stopped
        val preferences = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        if (preferences.getBoolean("flutter.service_should_run", false)) {
            val intent = Intent(applicationContext, LocationService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service", e)
            }
        }
    }
}
