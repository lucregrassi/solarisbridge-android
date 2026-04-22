# SOLARIS Bridge

Android apps for bridging DJI drone systems with an external PC over a local network.

The project contains two separate Android apps:

- **SOLARIS Bridge V4**: based on **DJI Mobile SDK V4**
- **SOLARIS Bridge V5**: based on **DJI Mobile SDK V5**

Both apps share a common goal:

- show the live camera preview on the Android device
- send telemetry to a PC
- receive flight commands from a PC
- receive gimbal commands from a PC
- stream video to a PC

The repository is organized into three main packages:

- `common`
- `v4`
- `v5`

---

## Project structure

### `common`
Shared utilities and reusable components used by both V4 and V5 apps.

Main responsibilities:

- network configuration persistence
- UDP/TCP streaming helpers
- command parsing
- telemetry models
- generic UDP receiver utilities

### `v4`
Implementation for DJI Mobile SDK V4.

Target workflow:

- local camera preview rendered on Android with `DJICodecManager`
- telemetry acquisition through V4 APIs
- flight control through V4 Virtual Stick
- gimbal control through V4 gimbal APIs
- video forwarding to PC using **JPEG frames captured from the Android preview**

### `v5`
Implementation for DJI Mobile SDK V5.

Target workflow:

- local camera preview attached through `MediaDataCenter.getInstance().cameraStreamManager`
- telemetry acquisition through DJI KeyManager listeners
- flight control through V5 Virtual Stick
- gimbal control through V5 KeyManager actions
- video forwarding to PC using the **encoded camera stream** received directly from the V5 camera stream manager

---

## Main features

Both app versions provide:

- live preview on the Android device
- telemetry streaming to PC over UDP
- flight command reception from PC over UDP
- gimbal command reception from PC over UDP
- configurable network settings from the app UI
- start/stop controls for:
  - video stream
  - telemetry stream
  - command receiver system

---

## Network configuration

The app stores network settings through `AppPrefs`.

Default values:

- **PC IP**: user-defined
- **Telemetry TX port**: `6000`
- **Video TX port**: `6001`
- **Flight command RX port**: `7000`
- **Gimbal command RX port**: `7001`

These are saved in Android `SharedPreferences` under:

- `pc_ip`
- `telemetry_tx_port`
- `video_tx_port`
- `flight_cmd_rx_port`
- `gimbal_cmd_rx_port`

---

## How the system works

The Android device acts as a bridge between the drone/controller side and a PC on the same network.

### Android -> PC
The app sends:

- **telemetry** via UDP
- **video** via TCP

### PC -> Android
The app receives:

- **flight commands** via UDP JSON
- **gimbal commands** via UDP JSON

---

## Telemetry streaming

Telemetry is sent to the PC every **100 ms**.

### Telemetry payload
The telemetry JSON includes fields such as:

- `ts`
- `lat`
- `lon`
- `ultrasonic_height`
- `vel_x`
- `vel_y`
- `vel_z`
- `roll`
- `pitch`
- `yaw`
- `battery_percent`
- `home_lat`
- `home_lon`
- `compass_heading`

### V4 telemetry
In V4, telemetry is gathered from:

- `FlightControllerState`
- `BatteryState`

and sent periodically by `TelemetryController`.

### V5 telemetry
In V5, telemetry is gathered by `TelemetryProvider` using DJI `KeyManager` listeners and then transmitted through `TelemetryStreamer`.

---

## Flight command system

Both versions implement a command receiver for flight control.

### Transport
- UDP
- JSON messages

### Default flight command RX port
- `7000`

### Expected command format
The parser expects a drone command structure with values like:

- `vx`
- `vy`
- `yaw`
- `throttle`

### Flight command loop
Both versions run a control send loop at:

- **20 Hz**
- period: **50 ms**

### Watchdog safety
Both versions include a watchdog that:

- monitors the arrival of flight commands
- triggers after about **250 ms** without updates
- sends a **zero command** when commands stop arriving

This helps avoid stale motion commands being held indefinitely.

---

## Gimbal command system

Both versions implement a dedicated UDP receiver for gimbal commands.

### Transport
- UDP
- JSON messages

### Default gimbal command RX port
- `7001`

### Expected command format
The parser expects a gimbal command structure with fields like:

- `yaw`
- `pitch`
- `roll`

### V4 gimbal control
V4 uses DJI V4 gimbal rotation APIs with:

- `Rotation`
- `RotationMode.ABSOLUTE_ANGLE`

### V5 gimbal control
V5 uses DJI V5 KeyManager actions with:

- `GimbalAngleRotation`
- `GimbalAngleRotationMode.ABSOLUTE_ANGLE`

and also supports a neutral stop/reset behavior.

---

## Video streaming

Video transmission differs significantly between SDK V4 and SDK V5.

---

## Video streaming in V4

### Local preview
In V4, the local preview on Android is rendered through:

- `VideoFeeder`
- `DJICodecManager`

The preview is displayed in a `TextureView`.

### PC streaming approach used in this project
In the final implementation, the V4 app sends video to the PC using:

- `JpegFrameStreamer`
- periodic capture of the Android `TextureView`
- JPEG compression
- TCP transmission to the PC

### Why JPEG in V4
During development, direct forwarding of the encoded stream through V4 was investigated, but the practical stable solution used here is the JPEG-based one.

This means:

- local preview remains visible on the device
- the PC receives frames extracted from the already-rendered preview
- the stream is simpler to decode on the PC
- latency is higher than direct encoded streaming

### Current V4 video characteristics
- source: rendered preview from `TextureView`
- protocol: TCP
- default port: `6001`
- default JPEG FPS: `8`
- default JPEG quality: `70`

### V4 packet format
Each frame is sent as:

- 4-byte magic: `VSTR`
- 1-byte packet type
- 4-byte payload length
- JPEG payload

Packet type for JPEG frames:

- `2`

---

## Video streaming in V5

### Local preview
In V5, preview is attached directly through:

- `MediaDataCenter.getInstance().cameraStreamManager`
- `putCameraStreamSurface(...)`

The preview surface is managed through a `TextureView` and Android `Surface`.

### PC streaming approach used in this project
In V5, the app sends the **encoded stream** directly to the PC through:

- `ICameraStreamManager.ReceiveStreamListener`
- `EncodedVideoStreamer`

This is the preferred near-live approach.

### Current V5 video characteristics
- source: encoded camera stream from DJI V5 APIs
- protocol: TCP
- default port: `6001`

### V5 packet format
Each frame is sent as:

- 4-byte magic: `VSTR`
- 4-byte payload length
- raw encoded frame payload

This stream is intended to be decoded on the PC side.

---

## Important difference between V4 and V5 video pipelines

### V4
The final working implementation in this project uses:

- **preview on Android**
- **JPEG capture from the preview**
- **JPEG forwarding to the PC**

This is robust, but introduces additional latency.

### V5
The final working implementation uses:

- **preview on Android**
- **direct encoded stream callback**
- **encoded frame forwarding to the PC**

This is more suitable for lower-latency live streaming.

---

## App screens

Each app includes:

### LauncherActivity
Checks whether the network configuration already exists.

If the PC IP is missing:

- opens `SettingsActivity`

Otherwise:

- opens `MainActivity`

### SettingsActivity
Allows configuration of:

- PC IP
- telemetry TX port
- video TX port
- flight command RX port
- gimbal command RX port

Validation includes:

- valid IPv4 format
- valid port range
- all ports must be different

### MainActivity
Main operational UI with:

- live preview
- video stream start/stop button
- telemetry stream start/stop button
- command receiver start/stop button
- command status text
- last received command text

---

## Common components

### `AppPrefs`
Stores and loads network settings from `SharedPreferences`.

### `TelemetryStreamer`
Shared UDP telemetry sender used by V5 and reusable by other modules.

### `EncodedVideoStreamer`
Shared TCP sender for raw/encoded video payloads.

### `JpegFrameStreamer`
Shared TCP sender for JPEG-compressed preview frames.

---

## V4 architecture summary

Main classes:

- `BridgeBootstrapV4`
- `BridgeAppV4`
- `LauncherActivity`
- `SettingsActivity`
- `MainActivity`
- `TelemetryController`
- `VideoStreamController`
- `CommandSystemController`
- `GimbalController`

### SDK registration
V4 uses `BridgeBootstrapV4` + `BridgeAppV4` to initialize the DJI SDK and connect to the product.

### Product access
The current product instance is accessed through:

- `BridgeAppV4.getProductInstance()`

### Flight control
Uses V4 `FlightController` virtual stick:

- velocity control for roll/pitch
- angular velocity control for yaw
- velocity control for vertical throttle
- body coordinate system

---

## V5 architecture summary

Main classes:

- `BridgeAppV5`
- `LauncherActivity`
- `SettingsActivity`
- `MainActivity`
- `TelemetryProvider`
- `TelemetryController`
- `VideoStreamController`
- `CommandSystemController`
- `GimbalController`

### SDK initialization
V5 initializes through:

- `SDKManager.getInstance().init(...)`
- `registerApp()`

### Preview
Preview is managed via:

- `MediaDataCenter`
- `ICameraStreamManager`
- `putCameraStreamSurface(...)`
- `removeCameraStreamSurface(...)`

### Flight control
Uses V5:

- `VirtualStickManager`
- `VirtualStickFlightControlParam`

### Telemetry
Uses V5:

- `KeyManager`
- typed DJI keys
- listen + fetch strategy

---

## PC side integration

The project is designed to work with a PC receiver application.

Typical PC responsibilities:

- listen for telemetry UDP packets on port `6000`
- listen for video TCP packets on port `6001`
- send flight command UDP JSON packets to port `7000`
- send gimbal command UDP JSON packets to port `7001`

### Example command directions
In the current system, flight commands are interpreted by the Android app and mapped into DJI virtual stick parameters.

Always validate axis mapping on the real platform before operational use.
---

## Build notes

This repository contains two Android application variants sharing common logic.

Typical requirements:

- Android Studio
- Gradle
- DJI Mobile SDK V4 dependencies for the V4 app
- DJI Mobile SDK V5 dependencies for the V5 app
- proper DJI app key / SDK registration configuration
- compatible Android device / smart controller
- drone/controller supported by the corresponding SDK version

---

## Safety notes

This project sends flight and gimbal commands to a real DJI system.

Use it carefully and always test in a safe environment before any real operation.

Recommended precautions:

- test first with propellers removed whenever possible
- verify command axis mapping before flight
- verify watchdog zero-command behavior
- keep a manual recovery procedure available at all times

---

## Author

Lucrezia Grassi  
GitHub: [lucregrassi](https://github.com/lucregrassi)  
Email: [lucrezia.grassi@unige.it](mailto:lucrezia.grassi@unige.it)
