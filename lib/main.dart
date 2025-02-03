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
      title: 'Location Activity Tracking',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
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
  static const platform = MethodChannel('com.test');
  bool isServiceRunning = false;

  // Credentials
  final Map<String, dynamic> credentials = {
    "orgId": 386,
    "empId": 8,
    "accessToken":
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJodHRwOi8vc2NoZW1hcy54bWxzb2FwLm9yZy93cy8yMDA1LzA1L2lkZW50aXR5L2NsYWltcy9uYW1lIjoiODIwMDI3MzIyNCIsImp0aSI6ImNiMmUyNmFmLTM0NmQtNDYxNy05MDRjLWUxZWFjZTM1MmVhYiIsImh0dHA6Ly9zY2hlbWFzLm1pY3Jvc29mdC5jb20vd3MvMjAwOC8wNi9pZGVudGl0eS9jbGFpbXMvcm9sZSI6IlVzZXIiLCJleHAiOjE3Mzk0NDE5MzEsImlzcyI6Imh0dHBzOi8vaHVtYW5lYy5haS8iLCJhdWQiOiJodHRwOi8vMTI3LjAuMC4xOjgwMDAvIn0.-yOMq89tVvxL5esH7Dy9wd5FVKkaBAOwTLCTCrCPGcY",
    "refreshToken": "vOshkO5pEQsNDgXIzPpLvTrOeMPd4I/o5EtfkiipPpU=",
    "img": "",
    "name": "Faizal Khalifa",
    "host": "https://api.geo.humanec.ai"
  };

  Future<void> toggleService() async {
    try {
      if (!isServiceRunning) {
        await platform.invokeMethod('userData',credentials);
        final bool result = await platform.invokeMethod('startLocationService');
        setState(() {
          isServiceRunning = result;
        });
      } else {
        await platform.invokeMethod('stopLocationService');
        setState(() {
          isServiceRunning = false;
        });
      }
    } on PlatformException catch (e) {
      debugPrint("Failed to toggle service: '${e.message}'.");
    }
  }

  Future<void> _requestPermissions() async {
    // First check if location services are enabled
    if (!await Permission.locationWhenInUse.serviceStatus.isEnabled) {
      setState(() {
        isServiceRunning = false;
      });
      return;
    }

    // Request notification permission for Android 13 and above
    if (await Permission.notification.isDenied) {
      final notificationStatus = await Permission.notification.request();
      if (!notificationStatus.isGranted) {
        setState(() {
          isServiceRunning = false;
        });
        return;
      }
    }

    // Request activity recognition permission
    if (await Permission.activityRecognition.isDenied) {
      final activityStatus = await Permission.activityRecognition.request();
      if (!activityStatus.isGranted) {
        setState(() {
          isServiceRunning = false;
        });
        return;
      }
    }

    // Request location permissions
    final locationStatus = await Permission.locationWhenInUse.request();
    if (!locationStatus.isGranted) {
      setState(() {
        isServiceRunning = false;
      });
      return;
    }

    // Request background location permission
    final backgroundStatus = await Permission.locationAlways.request();
    if (!backgroundStatus.isGranted) {
      setState(() {
        isServiceRunning = false;
      });
      return;
    }

    // Open app settings if any permission is permanently denied
    if (await Permission.locationAlways.isPermanentlyDenied ||
        await Permission.activityRecognition.isPermanentlyDenied ||
        await Permission.notification.isPermanentlyDenied) {
      setState(() {
        isServiceRunning = false;
      });
      await openAppSettings();
      return;
    }
  }

  @override
  void initState() {
    super.initState();
    _requestPermissions();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Location Activity Tracking'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              isServiceRunning ? 'Service is Running' : 'Service is Stopped',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 20),
            ElevatedButton(
              onPressed: toggleService,
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 40, vertical: 15),
              ),
              child: Text(
                isServiceRunning ? 'Stop Service' : 'Start Service',
                style: const TextStyle(fontSize: 18),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
