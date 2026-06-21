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
- **autonomous GPS navigation through V4 Waypoint Missions** (go-to a lat/lon target)
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

The **V4** app additionally provides **autonomous GPS navigation** (go-to a lat/lon target via
Waypoint Missions), coordinated with manual Virtual Stick control. See the dedicated section below.

---

## Network configuration

Network settings are stored through `AppPrefs`.

Default values:

- **Telemetry TX port**: `6000`
- **Video TX port**: `6001`
- **Flight command RX port**: `7000`
- **Gimbal command RX port**: `7001`
- **Waypoint (goto) command RX port**: `7002` *(V4 only)*

Saved keys in `SharedPreferences`:

- `pc_ip`
- `telemetry_tx_port`
- `video_tx_port`
- `flight_cmd_rx_port`
- `gimbal_cmd_rx_port`
- `waypoint_cmd_rx_port` *(V4 only; default 7002, configurable in the V4 settings screen)*

---

## Data flow

### Android -> PC
- **telemetry** via UDP
- **video** via TCP

### PC -> Android
- **flight commands** via UDP JSON
- **gimbal commands** via UDP JSON
- **waypoint (goto) commands** via UDP JSON *(V4 only)*

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

The V4 app additionally reports:

- `altitude_rel_takeoff` — aircraft altitude relative to the takeoff point (used as the waypoint altitude reference)
- `is_flying` — whether the aircraft is currently airborne
- `goto_state` — current state of the autonomous navigation: `idle` / `enroute` / `arrived` / `failed`

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

## Autonomous GPS navigation (V4 only)

In addition to manual Virtual Stick control, the **V4** app can fly the aircraft autonomously
to a GPS target using DJI **Waypoint Missions**. This was designed for solar-panel monitoring:
the PC drives the aircraft manually (e.g. visual servoing along a panel), then issues a goto to
reposition the drone to the start of the next panel, and resumes manual control once it arrives.

### Goto command

Sent by the PC via UDP JSON on the waypoint port (default `7002`):

```json
{ "lat": 44.4012, "lon": 8.9560, "alt": 20.0, "speed": 3.0, "heading": 90.0 }
```

- `lat`, `lon` — target coordinates (WGS84 decimal degrees)
- `alt` — target altitude in metres **relative to the takeoff point**
- `speed` — cruise speed in m/s (mapped to `autoFlightSpeed`/`maxFlightSpeed`)
- `heading` — *optional*. If present, the aircraft reaches the target with this heading
  (`-180..180`, relative to north); if omitted, the heading follows the direction of travel.

A 2-waypoint mission is built (current position → target) with `finishedAction = NO_ACTION`,
so the aircraft **hovers** at the destination and waits for the PC to take over.

### Control state machine

A `ControlCoordinator` arbitrates between Virtual Stick and Waypoint Mission. The Virtual Stick
control logic is unchanged; the coordinator only drives it from the outside. The command button
in `MainActivity` arms/disarms the whole PC control surface (manual **and** mission).

- **IDLE** — PC control off. Virtual Stick off; the aircraft hovers under its own GPS hold.
- **MANUAL** — PC control on. Virtual Stick active; flight (`7000`), gimbal (`7001`) and goto
  (`7002`) channels listening.
- **MISSION** — a valid goto suspends the Virtual Stick (the `7000`/`7001` receivers stay alive)
  and runs the Waypoint Mission.

Transitions:

- **MANUAL → MISSION**: a goto is validated (GPS healthy, aircraft flying, distance within range)
  and started. An invalid goto is rejected and the state is left unchanged.
- **MISSION → MANUAL (arrived)**: on mission completion the Virtual Stick is re-enabled **first**,
  then `goto_state=arrived` is published, so the PC can safely resume sending velocity.
- **MISSION → MANUAL (override)**: a valid, non-zero flight command on `7000` during a mission
  aborts it and immediately returns to manual control.
- **MISSION → MISSION (replace)**: a new goto aborts the current mission and starts a new one.
- **MISSION → MANUAL (fault)**: on GPS loss / mission error the aircraft hovers and control
  returns to Virtual Stick with `goto_state=failed`. On **radio-controller loss** the on-board
  failsafe takes over (per the aircraft's own signal-loss configuration; not forced by the app).
- **disarm** (command button off): any running mission is aborted and the aircraft hovers.

### Handshake with the PC

The PC publishes a goto, then waits until telemetry reports `goto_state=arrived` before it starts
sending Virtual Stick velocity again. The `goto_state` field is the synchronization point between
autonomous repositioning and manual (visual-servoing) control.

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

Validation includes IPv4 format, valid port range, and unique ports. *(The V4 screen also exposes
the waypoint/goto port, default `7002`.)*

### `MainActivity`
Provides:

- live preview
- video stream start/stop
- telemetry stream start/stop
- PC control arm/disarm (Virtual Stick **and**, on V4, Waypoint Mission)
- command status text
- last received command text

On V4 the command button arms the whole PC control surface: while armed the app accepts manual
flight commands, gimbal commands, and goto/waypoint commands, and reports the navigation state
through `goto_state` in telemetry.

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

Additional notes for the V4 autonomous navigation:

* a valid, non-zero flight command on the flight port aborts a running mission and returns to
  manual control (manual override); this abort is not instantaneous, as `stopMission` is asynchronous
* on radio-controller signal loss the aircraft's own configured failsafe takes over (the app does
  not force a behaviour) — configure and verify it (e.g. RTH altitude and a valid home point) in
  the DJI settings before relying on it
* always verify the suspend → mission → resume handover **with propellers removed** before any
  real flight, and confirm the goto altitude reference (relative to takeoff) matches your PC side
* `MainActivity` has a debug flag `DEV_BYPASS_GOTO_PRECONDITIONS` that skips the GPS/flying checks
  for bench testing without GPS. It **must stay `false`** for any real flight, and be used only
  with propellers removed

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
