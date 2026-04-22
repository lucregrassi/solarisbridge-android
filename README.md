# SOLARIS Bridge

<p align="center">
  <img src="solaris-bridge-banner.png" alt="SOLARIS Bridge banner" width="900">
</p>

Android apps for bridging DJI drone systems with an external PC over a local network.

The project contains two separate Android apps:

- **SOLARIS Bridge V4**, based on **DJI Mobile SDK V4**
- **SOLARIS Bridge V5**, based on **DJI Mobile SDK V5**

Both apps are designed to:

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
Shared utilities and reusable components for both apps, including:

- network configuration persistence
- UDP/TCP streaming helpers
- command parsing
- telemetry models
- generic UDP receiver utilities

### `v4`
Implementation based on DJI Mobile SDK V4.

Main workflow:

- local preview with `DJICodecManager`
- telemetry through V4 APIs
- flight control through V4 Virtual Stick
- gimbal control through V4 gimbal APIs
- video streaming to PC using **JPEG frames captured from the Android preview**

### `v5`
Implementation based on DJI Mobile SDK V5.

Main workflow:

- local preview through `MediaDataCenter.getInstance().cameraStreamManager`
- telemetry through DJI `KeyManager`
- flight control through V5 Virtual Stick
- gimbal control through V5 KeyManager actions
- video streaming to PC using the **encoded camera stream** received from the V5 camera stream manager

---

## Main features

Both app versions provide:

- live preview on the Android device
- telemetry streaming to PC over UDP
- flight command reception from PC over UDP
- gimbal command reception from PC over UDP
- configurable network settings from the app UI
- start/stop controls for video, telemetry, and command reception

---

## Network configuration

Network settings are stored through `AppPrefs`.

Default values:

- **Telemetry TX port**: `6000`
- **Video TX port**: `6001`
- **Flight command RX port**: `7000`
- **Gimbal command RX port**: `7001`

Saved keys in `SharedPreferences`:

- `pc_ip`
- `telemetry_tx_port`
- `video_tx_port`
- `flight_cmd_rx_port`
- `gimbal_cmd_rx_port`

---

## Data flow

### Android -> PC
- **telemetry** via UDP
- **video** via TCP

### PC -> Android
- **flight commands** via UDP JSON
- **gimbal commands** via UDP JSON

---

## Telemetry and command system

Telemetry is sent every **100 ms** and includes fields such as:

- `ts`
- `lat`
- `lon`
- `ultrasonic_height`
- `vel_x`, `vel_y`, `vel_z`
- `roll`, `pitch`, `yaw`
- `battery_percent`
- `home_lat`, `home_lon`
- `compass_heading`

Flight commands are received via UDP JSON on port `7000` and use values such as:

- `vx`
- `vy`
- `yaw`
- `throttle`

Gimbal commands are received via UDP JSON on port `7001` and use:

- `yaw`
- `pitch`
- `roll`

Both V4 and V5 run the flight command send loop at **20 Hz** and include a watchdog that sends a zero command if updates stop arriving for about **250 ms**.

---

## Video streaming

Video handling differs between V4 and V5.

### V4
V4 shows the local preview through `VideoFeeder` and `DJICodecManager`, rendering it inside a `TextureView`.

In the final implementation, video is sent to the PC by capturing the rendered preview and forwarding it as JPEG frames through `JpegFrameStreamer`.

Current characteristics:

- source: rendered preview from `TextureView`
- protocol: TCP
- default port: `6001`
- default JPEG FPS: `8`
- default JPEG quality: `70`

Packet format:

- 4-byte magic: `VSTR`
- 1-byte packet type
- 4-byte payload length
- JPEG payload

JPEG packet type:

- `2`

This solution is stable and keeps the local preview visible, but it introduces more latency.

### V5
V5 attaches preview directly through `MediaDataCenter.getInstance().cameraStreamManager` and an Android `Surface`.

In the final implementation, the PC stream uses the encoded camera stream received through `ICameraStreamManager.ReceiveStreamListener` and forwarded through `EncodedVideoStreamer`.

Current characteristics:

- source: encoded camera stream from DJI V5 APIs
- protocol: TCP
- default port: `6001`

Packet format:

- 4-byte magic: `VSTR`
- 4-byte payload length
- raw encoded frame payload

This is the preferred lower-latency solution.

---

## App screens

Each app includes:

### `LauncherActivity`
Opens `SettingsActivity` if configuration is missing, otherwise opens `MainActivity`.

### `SettingsActivity`
Lets the user configure:

- PC IP
- telemetry TX port
- video TX port
- flight command RX port
- gimbal command RX port

Validation includes IPv4 format, valid port range, and unique ports.

### `MainActivity`
Provides:

- live preview
- video stream start/stop
- telemetry stream start/stop
- command receiver start/stop
- command status text
- last received command text

---

## Setup

Before building the project, you must provide your own DJI API keys in `local.properties`.

Example:

```properties
DJI_API_KEY_V4=your_v4_key_here
DJI_API_KEY_V5=your_v5_key_here
```

You also need:

* Android Studio
* Gradle
* DJI Mobile SDK V4 dependencies for the V4 app
* DJI Mobile SDK V5 dependencies for the V5 app
* compatible Android device / smart controller
* drone/controller supported by the corresponding SDK version

Do not commit real DJI keys to the repository.

---
## Safety notes

This project sends flight and gimbal commands to a real DJI system.

Use it carefully and always test in a safe environment before any real operation.

Recommended precautions:

* test first with propellers removed whenever possible
* verify command axis mapping before flight
* verify watchdog zero-command behavior
* keep a manual recovery procedure available at all times
* in our tests, switching the controller to S mode interrupted PC-driven command input and the aircraft returned to a stable hover state
* always validate this behavior on your own hardware and firmware before relying on it as a safety procedure

---
## Acknowledgment

This repository was developed within the framework of the European Union SOLARIS project.

The work was supported by the European Union under the SOLARIS project (grant agreement no. 101146377).

More information is available on the [SOLARIS project website](https://solaris-heu.eu/).

---

## Author

Lucrezia Grassi  
GitHub: [lucregrassi](https://github.com/lucregrassi)  
Email: [lucrezia.grassi@unige.it](mailto:lucrezia.grassi@unige.it)
