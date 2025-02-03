import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: const MyHomePage(),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key});

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  static const methodChannel = MethodChannel('com.test');
  static const locationEventChannel = EventChannel('com.test/location_updates');
  static const activityEventChannel = EventChannel('com.test/activity_updates');

  String locationInfo = 'No location updates';
  String activityInfo = 'No activity detected';
  bool isServiceRunning = false;
  StreamSubscription? locationSubscription;
  StreamSubscription? activitySubscription;

  @override
  void initState() {
    super.initState();
  }

  void _setupStreams() {
    try {
      locationSubscription?.cancel();
      activitySubscription?.cancel();

      locationSubscription =
          locationEventChannel.receiveBroadcastStream().listen((dynamic event) {
        setState(() {
          if (event is Map && event.containsKey('error')) {
            locationInfo = 'Error: ${event['error']}';
            debugPrint('Location error: ${event['error']}');
          } else {
            locationInfo =
                'Location: ${event['latitude']}, ${event['longitude']}\n'
                'Accuracy: ${event['accuracy']} meters\n'
                'Speed: ${event['speed']} m/s\n'
                'Time: ${DateTime.fromMillisecondsSinceEpoch(event['time'] as int)}';
            debugPrint('Location update received: $event');
          }
        });
      }, onError: (dynamic error) {
        setState(() {
          locationInfo = 'Error receiving location updates: $error';
        });
        debugPrint('Error in location stream: $error');
      });

      activitySubscription =
          activityEventChannel.receiveBroadcastStream().listen((dynamic event) {
        setState(() {
          activityInfo = 'Activity: ${event['activityType']}\n'
              'Time: ${DateTime.fromMillisecondsSinceEpoch(event['time'] as int)}';
        });
        debugPrint('Activity update received: $event');
      }, onError: (dynamic error) {
        setState(() {
          activityInfo = 'Error receiving activity updates: $error';
        });
        debugPrint('Error in activity stream: $error');
      });
    } catch (e) {
      debugPrint('Error setting up streams: $e');
      setState(() {
        locationInfo = 'Failed to set up location updates';
        activityInfo = 'Failed to set up activity updates';
      });
    }
  }

  Future<void> _startLocationService() async {
    try {
      if (await _requestPermissions()) {
        await methodChannel.invokeMethod('startLocationService');
        setState(() {
          isServiceRunning = true;
          locationInfo = 'Waiting for location updates...';
          activityInfo = 'Waiting for activity updates...';
        });
        _setupStreams();
        debugPrint('Location service started successfully');
      } else {
        setState(() {
          locationInfo = 'Failed to get required permissions';
        });
        debugPrint('Failed to get required permissions');
      }
    } on PlatformException catch (e) {
      debugPrint("Failed to start service: ${e.message}");
      setState(() {
        locationInfo = 'Failed to start location service: ${e.message}';
      });
    }
  }

  Future<void> _stopLocationService() async {
    try {
      await methodChannel.invokeMethod('stopLocationService');
      setState(() {
        isServiceRunning = false;
        locationInfo = 'Location tracking stopped';
        activityInfo = 'Activity tracking stopped';
      });
      locationSubscription?.cancel();
      activitySubscription?.cancel();
    } on PlatformException catch (e) {
      debugPrint("Failed to stop service: ${e.message}");
      setState(() {
        locationInfo = 'Failed to stop service: ${e.message}';
      });
    }
  }

  Future<void> _setUserData() async {
    try {
      await methodChannel.invokeMethod('userData', {
        "orgId": 386,
        "empId": 8,
        "accessToken":
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJodHRwOi8vc2NoZW1hcy54bWxzb2FwLm9yZy93cy8yMDA1LzA1L2lkZW50aXR5L2NsYWltcy9uYW1lIjoiODIwMDI3MzIyNCIsImp0aSI6ImNiMmUyNmFmLTM0NmQtNDYxNy05MDRjLWUxZWFjZTM1MmVhYiIsImh0dHA6Ly9zY2hlbWFzLm1pY3Jvc29mdC5jb20vd3MvMjAwOC8wNi9pZGVudGl0eS9jbGFpbXMvcm9sZSI6IlVzZXIiLCJleHAiOjE3Mzk0NDE5MzEsImlzcyI6Imh0dHBzOi8vaHVtYW5lYy5haS8iLCJhdWQiOiJodHRwOi8vMTI3LjAuMC4xOjgwMDAvIn0.-yOMq89tVvxL5esH7Dy9wd5FVKkaBAOwTLCTCrCPGcY",
        "refreshToken":"vOshkO5pEQsNDgXIzPpLvTrOeMPd4I/o5EtfkiipPpU=",
        "img": "",
        "name": "Faizal Khalifa",
        "host": "https://api.geo.humanec.ai"
      });
      debugPrint('User data set successfully');
    } catch (e) {
      debugPrint("Failed to set user data: ${e.toString()}");
    }
  }

  Future<bool> _requestPermissions() async {
    // First check if location services are enabled
    if (!await Permission.locationWhenInUse.serviceStatus.isEnabled) {
      setState(() {
        locationInfo = 'Please enable location services in settings';
      });
      return false;
    }

    // Request notification permission for Android 13 and above
    if (await Permission.notification.isDenied) {
      final notificationStatus = await Permission.notification.request();
      if (!notificationStatus.isGranted) {
        setState(() {
          locationInfo = 'Notification permission is required';
        });
        return false;
      }
    }

    // Request activity recognition permission
    if (await Permission.activityRecognition.isDenied) {
      final activityStatus = await Permission.activityRecognition.request();
      if (!activityStatus.isGranted) {
        setState(() {
          activityInfo = 'Activity recognition permission is required';
        });
        return false;
      }
    }

    // Request location permissions
    final locationStatus = await Permission.locationWhenInUse.request();
    if (!locationStatus.isGranted) {
      setState(() {
        locationInfo = 'Location permission is required';
      });
      return false;
    }

    // Request background location permission
    final backgroundStatus = await Permission.locationAlways.request();
    if (!backgroundStatus.isGranted) {
      setState(() {
        locationInfo = 'Background location permission is required';
      });
      return false;
    }

    // Open app settings if any permission is permanently denied
    if (await Permission.locationAlways.isPermanentlyDenied ||
        await Permission.activityRecognition.isPermanentlyDenied ||
        await Permission.notification.isPermanentlyDenied) {
      setState(() {
        locationInfo = 'Please enable permissions in app settings';
      });
      await openAppSettings();
      return false;
    }

    return true;
  }

  @override
  void dispose() {
    locationSubscription?.cancel();
    activitySubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Location & Activity Tracker'),
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      const Text(
                        'Location Updates',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(locationInfo),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      const Text(
                        'Activity Updates',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(activityInfo),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),
              ElevatedButton(
                onPressed: isServiceRunning
                    ? _stopLocationService
                    : _startLocationService,
                child:
                    Text(isServiceRunning ? 'Stop Tracking' : 'Start Tracking'),
              ),
              const SizedBox(height: 24),
              ElevatedButton(
                onPressed: () {
                  _setUserData();
                },
                child: Text("Set User Data"),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
