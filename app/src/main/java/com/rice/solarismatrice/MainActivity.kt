package com.rice.solarismatrice

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import dji.v5.manager.KeyManager
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalSpeedRotation
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode

import dji.v5.manager.aircraft.virtualstick.VirtualStickManager

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private val TAG = "MainActivity"

    // ===================== UI =====================
    private lateinit var videoTexture: TextureView
    private lateinit var btnVideo: MaterialButton
    private lateinit var btnTelem: MaterialButton
    private lateinit var tvVideoStatus: TextView
    private lateinit var tvTelemStatus: TextView

    private lateinit var btnCmd: MaterialButton
    private lateinit var tvCmdStatus: TextView
    private lateinit var tvCmdLast: TextView

    // ===================== Preview DJI =====================
    private var surface: Surface? = null
    private var attached = false
    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN

    private val streamManager: ICameraStreamManager?
        get() = MediaDataCenter.getInstance().cameraStreamManager

    // ===================== Streamers =====================
    private var videoStreamer: ImageStreamer? = null
    private var telemetryStreamer: TelemetryStreamer? = null

    private var telemTimer: Timer? = null
    private var videoRunning = false
    private var telemRunning = false

    private var nv21Listener: ICameraStreamManager.CameraFrameListener? = null

    // ===================== CMD system (UDP 7000/7001) =====================
    private var droneRx: UdpJsonReceiver? = null
    private var gimbalRx: UdpJsonReceiver? = null
    private var cmdRunning = false

    @Volatile private var lastDroneCmd: DroneCmd? = null
    @Volatile private var vsEnabled = false

    private val vsManager by lazy { VirtualStickManager.getInstance() }

    private val droneTxHandler = Handler(Looper.getMainLooper())
    private var droneTxRunning = false
    private val droneTxPeriodMs = 50L   // 20 Hz

    private val droneCmdWatchdog = CmdWatchdog(
        tag = "DroneCmdWatchdog",
        tickMs = 50L,
        timeoutMs = 250L
    ) {
        Log.w(TAG, "Drone watchdog timeout -> mando ZERO")
        lastDroneCmd = zeroDroneCmd()
        sendVirtualStickNow(lastDroneCmd!!)
    }

    private val droneTxRunnable = object : Runnable {
        override fun run() {
            if (!droneTxRunning) return

            val cmd = lastDroneCmd ?: zeroDroneCmd()
            sendVirtualStickNow(cmd)

            droneTxHandler.postDelayed(this, droneTxPeriodMs)
        }
    }

    private val DRONE_PORT = 7000
    private val GIMBAL_PORT = 7001
    private val EXPECTED_HZ = 20

    // Gimbal index (di solito 0)
    private val GIMBAL_INDEX = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        if (AppPrefs.getPcIp(this).isNullOrBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        // UI bind
        videoTexture = findViewById(R.id.videoTexture)
        btnVideo = findViewById(R.id.btnVideo)
        btnTelem = findViewById(R.id.btnTelem)
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        tvTelemStatus = findViewById(R.id.tvTelemStatus)

        btnCmd = findViewById(R.id.btnCmd)
        tvCmdStatus = findViewById(R.id.tvCmdStatus)
        tvCmdLast = findViewById(R.id.tvCmdLast)

        // Listeners
        videoTexture.surfaceTextureListener = this
        btnVideo.setOnClickListener { toggleVideoStream() }
        btnTelem.setOnClickListener { toggleTelemetry() }
        btnCmd.setOnClickListener { toggleCmdSystem() }

        // Build networking pieces
        buildStreamersFromPrefs()
        buildCmdSystem()

        renderUiState()
    }

    override fun onResume() {
        super.onResume()
        buildStreamersFromPrefs()
        renderUiState()
        maybeAttachPreview()
    }

    override fun onPause() {
        // Non forzare "neutro" su pause: lo facciamo solo quando premi STOP CMD
        stopCmdSystem(moveGimbalToNeutral = false)
        stopVideoStream()
        stopTelemetryIfNeeded()
        detachPreview()
        super.onPause()
    }

    override fun onDestroy() {
        stopCmdSystem(moveGimbalToNeutral = false)
        stopVideoStream()
        stopTelemetryIfNeeded()
        detachPreview()
        super.onDestroy()
    }

    // ===================== Build Streamers =====================

    private fun buildStreamersFromPrefs() {
        val ip = AppPrefs.getPcIp(this) ?: return
        val videoPort = AppPrefs.getVideoPort(this)
        val telemPort = AppPrefs.getTelemPort(this)

        if (videoRunning) stopVideoStream()
        if (telemRunning) stopTelemetryIfNeeded()

        videoStreamer = ImageStreamer(ip, videoPort)
        telemetryStreamer = TelemetryStreamer(ip, telemPort)
    }

    // ===================== CMD System =====================

    private fun buildCmdSystem() {
        droneRx = UdpJsonReceiver(
            port = DRONE_PORT,
            tag = "UDP-DRONE"
        ) { json, from ->
            val cmd = CmdParsers.parseDrone(json)
            if (cmd != null) {
                lastDroneCmd = cmd
                droneCmdWatchdog.lastDroneRxMs = SystemClock.elapsedRealtime()

                Log.i(TAG, "DRONE RX $from -> $cmd")
                showCmdLine("DRONE: $cmd")
            } else {
                Log.w(TAG, "DRONE parse FAIL $from -> $json")
                showCmdLine("DRONE parse FAIL: $json")
            }
        }

        gimbalRx = UdpJsonReceiver(
            port = GIMBAL_PORT,
            tag = "UDP-GIMBAL"
        ) { json, from ->
            val cmd = CmdParsers.parseGimbal(json)
            if (cmd != null) {
                Log.i(TAG, "GIMBAL RX $from -> $cmd")
                showCmdLine("GIMBAL: $cmd")

                // invio reale alla gimbal SOLO quando CMD è attivo
                if (cmdRunning) applyGimbalCmd(cmd)
            } else {
                Log.w(TAG, "GIMBAL parse FAIL $from -> $json")
                showCmdLine("GIMBAL parse FAIL: $json")
            }
        }
    }

    private fun toggleCmdSystem() {
        if (!cmdRunning) startCmdSystem() else stopCmdSystem(moveGimbalToNeutral = true)
        renderCmdUiState()
    }

    private fun startCmdSystem() {
        if (cmdRunning) return
        enableVirtualStickAndStart()
    }

    private fun stopCmdSystem(moveGimbalToNeutral: Boolean) {
        if (!cmdRunning) return
        disableVirtualStickAndStop(moveGimbalToNeutral)
    }

    private fun showCmdLine(line: String) {
        runOnUiThread { tvCmdLast.text = "Last cmd: $line" }
    }

    // ===================== DJI GIMBAL CONTROL =====================

    private fun applyGimbalCmd(cmd: GimbalCmd) {
        // Angoli assoluti in gradi
        sendGimbalAngleCommand(
            yawDeg = cmd.yaw.toDouble(),
            pitchDeg = cmd.pitch.toDouble(),
            rollDeg = cmd.roll.toDouble(),
            durationSec = 0.08 // un filo più “sincrono” con 20Hz
        )
    }

    private fun gimbalToNeutral() {
        // Stop (best effort) e poi vai in neutro 0,0,0
        stopGimbalMotion()
        sendGimbalAngleCommand(
            yawDeg = 0.0,
            pitchDeg = 0.0,
            rollDeg = 0.0,
            durationSec = 0.8
        )
    }

    private fun sendGimbalAngleCommand(
        yawDeg: Double,
        pitchDeg: Double,
        rollDeg: Double,
        durationSec: Double
    ) {
        val rotation = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            yaw = yawDeg
            pitch = pitchDeg
            roll = rollDeg
            duration = durationSec
        }

        val key = KeyTools.createKey(GimbalKey.KeyRotateByAngle, GIMBAL_INDEX)

        KeyManager.getInstance().performAction(
            key,
            rotation,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) { }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Gimbal rotate failed: ${error.description()}")
                }
            }
        )
    }

    private fun stopGimbalMotion() {
        val speed = GimbalSpeedRotation().apply {
            yaw = 0.0
            pitch = 0.0
            roll = 0.0
        }

        val key = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, GIMBAL_INDEX)
        KeyManager.getInstance().performAction(
            key,
            speed,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {}
                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "Gimbal stop failed: ${error.description()}")
                }
            }
        )
    }

    // ===================== UI rendering =====================

    private fun renderUiState() {
        val green = ColorStateList.valueOf(getColor(R.color.state_green))
        val red = ColorStateList.valueOf(getColor(R.color.state_red))

        // VIDEO
        if (videoRunning) {
            btnVideo.text = "STOP VIDEO"
            btnVideo.backgroundTintList = red
            tvVideoStatus.text = "VIDEO: ON"
            tvVideoStatus.setTextColor(getColor(R.color.state_green))
        } else {
            btnVideo.text = "START VIDEO"
            btnVideo.backgroundTintList = green
            tvVideoStatus.text = "VIDEO: OFF"
            tvVideoStatus.setTextColor(getColor(R.color.state_red))
        }

        // TELEMETRY
        if (telemRunning) {
            btnTelem.text = "STOP TELEMETRY"
            btnTelem.backgroundTintList = red
            tvTelemStatus.text = "TELEMETRY: ON"
            tvTelemStatus.setTextColor(getColor(R.color.state_green))
        } else {
            btnTelem.text = "START TELEMETRY"
            btnTelem.backgroundTintList = green
            tvTelemStatus.text = "TELEMETRY: OFF"
            tvTelemStatus.setTextColor(getColor(R.color.state_red))
        }

        renderCmdUiState()
    }

    private fun renderCmdUiState() {
        val green = ColorStateList.valueOf(getColor(R.color.state_green))
        val red = ColorStateList.valueOf(getColor(R.color.state_red))

        if (cmdRunning) {
            btnCmd.text = "STOP CMD"
            btnCmd.backgroundTintList = red
            tvCmdStatus.text = "CMD: ON (${EXPECTED_HZ}Hz)"
            tvCmdStatus.setTextColor(getColor(R.color.state_green))
        } else {
            btnCmd.text = "START CMD"
            btnCmd.backgroundTintList = green
            tvCmdStatus.text = "CMD: OFF"
            tvCmdStatus.setTextColor(getColor(R.color.state_red))
        }
    }

    // ===================== PREVIEW DJI =====================

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        attachPreview(w, h)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        detachPreview()
        surface = Surface(st)
        attachPreview(w, h)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        detachPreview()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun maybeAttachPreview() {
        val st = videoTexture.surfaceTexture ?: return
        if (surface == null) surface = Surface(st)
        attachPreview(videoTexture.width, videoTexture.height)
    }

    private fun attachPreview(w: Int, h: Int) {
        val s = surface ?: return
        val mgr = streamManager ?: run {
            Log.w(TAG, "CameraStreamManager non disponibile")
            return
        }
        if (attached || w <= 0 || h <= 0) return

        mgr.putCameraStreamSurface(
            cameraIndex,
            s,
            w,
            h,
            ICameraStreamManager.ScaleType.CENTER_INSIDE
        )
        attached = true
        Log.i(TAG, "Preview ATTACCATA: index=$cameraIndex size=${w}x$h")
    }

    private fun detachPreview() {
        val s = surface ?: return
        if (!attached) return
        try { streamManager?.removeCameraStreamSurface(s) } catch (_: Throwable) {}
        attached = false
        Log.i(TAG, "Preview STACCATA")
    }

    // ===================== VIDEO (frames) TO PC =====================

    private fun toggleVideoStream() {
        if (!videoRunning) startVideoStream() else stopVideoStream()
        renderUiState()
    }

    private fun startVideoStream() {
        val mgr = streamManager ?: run {
            Log.w(TAG, "CameraStreamManager null")
            return
        }

        if (!attached) {
            Log.w(TAG, "Preview not attached yet")
            return
        }
        if (videoRunning) return

        videoStreamer?.start()

        val listener = ICameraStreamManager.CameraFrameListener { data, offset, length, width, height, _ ->
            if (!videoRunning) return@CameraFrameListener
            if (length <= 0) return@CameraFrameListener

            val frame = ByteArray(length)
            System.arraycopy(data, offset, frame, 0, length)
            videoStreamer?.sendRawYuv(frame, width, height)
        }

        nv21Listener = listener

        mgr.addFrameListener(
            cameraIndex,
            ICameraStreamManager.FrameFormat.NV21,
            listener
        )

        videoRunning = true
        Log.i(TAG, "Video stream STARTED")
    }

    private fun stopVideoStream() {
        if (!videoRunning) return

        val mgr = streamManager
        nv21Listener?.let { listener ->
            try { mgr?.removeFrameListener(listener) } catch (t: Throwable) {
                Log.w(TAG, "removeFrameListener error", t)
            }
        }

        nv21Listener = null
        videoStreamer?.stop()

        videoRunning = false
        Log.i(TAG, "Video stream STOPPED")
    }

    // ===================== TELEMETRY TO PC =====================

    private fun toggleTelemetry() {
        if (!telemRunning) startTelemetryIfNeeded() else stopTelemetryIfNeeded()
        renderUiState()
    }

    private fun startTelemetryIfNeeded() {
        if (telemRunning) return

        TelemetryProvider.startIfNeeded()
        telemetryStreamer?.start()

        telemTimer = fixedRateTimer(name = "telem", daemon = true, initialDelay = 0L, period = 100L) {
            val snap = TelemetryProvider.getSnapshot()
            telemetryStreamer?.sendCurrentSnapshot(snap)
        }

        telemRunning = true
        Log.i(TAG, "Telemetry stream STARTED (UDP)")
    }

    private fun stopTelemetryIfNeeded() {
        if (!telemRunning) return

        telemTimer?.cancel()
        telemTimer = null

        telemetryStreamer?.stop()
        TelemetryProvider.stopIfNeeded()

        telemRunning = false
        Log.i(TAG, "Telemetry stream STOPPED")
    }

    // ===================== Menu =====================

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.app_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.menu_main -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ======== Virtual stick
    private fun clamp(v: Float, min: Float, max: Float): Double {
        return v.coerceIn(min, max).toDouble()
    }

    private fun zeroDroneCmd() = DroneCmd(
        vx = 0f,
        vy = 0f,
        yaw = 0f,
        throttle = 0f
    )

    private fun buildVirtualStickParam(cmd: DroneCmd): VirtualStickFlightControlParam {
        return VirtualStickFlightControlParam().apply {
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

            pitch = clamp(cmd.vx, -23f, 23f)
            roll = clamp(cmd.vy, -23f, 23f)
            yaw = clamp(cmd.yaw, -100f, 100f)
            verticalThrottle = clamp(cmd.throttle, -6f, 6f)
        }
    }

    private fun sendVirtualStickNow(cmd: DroneCmd) {
        if (!vsEnabled) return
        try {
            val param = buildVirtualStickParam(cmd)
            vsManager.sendVirtualStickAdvancedParam(param)
        } catch (t: Throwable) {
            Log.e(TAG, "sendVirtualStickAdvancedParam failed", t)
        }
    }

    private fun startDroneSenderLoop() {
        if (droneTxRunning) return
        droneTxRunning = true
        droneTxHandler.post(droneTxRunnable)
    }

    private fun stopDroneSenderLoop() {
        droneTxRunning = false
        droneTxHandler.removeCallbacks(droneTxRunnable)
    }

    private fun enableVirtualStickAndStart() {
        vsManager.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.i(TAG, "Virtual Stick ENABLED")
                vsEnabled = true

                vsManager.setVirtualStickAdvancedModeEnabled(true)

                lastDroneCmd = zeroDroneCmd()
                sendVirtualStickNow(lastDroneCmd!!)

                droneCmdWatchdog.lastDroneRxMs = SystemClock.elapsedRealtime()
                droneCmdWatchdog.start()
                startDroneSenderLoop()

                cmdRunning = true
                droneRx?.start()
                gimbalRx?.start()

                runOnUiThread {
                    Log.i(TAG, "CMD system STARTED (Virtual Stick + UDP)")
                    showCmdLine("CMD system STARTED")
                    renderCmdUiState()
                }
            }

            override fun onFailure(error: IDJIError) {
                vsEnabled = false
                cmdRunning = false
                Log.e(TAG, "enableVirtualStick failed: ${error.description()}")

                runOnUiThread {
                    showCmdLine("VS enable FAIL: ${error.description()}")
                    renderCmdUiState()
                }
            }
        })
    }

    private fun disableVirtualStickAndStop(moveGimbalToNeutral: Boolean) {
        cmdRunning = false
        stopDroneSenderLoop()
        droneCmdWatchdog.stop()

        droneRx?.stop()
        gimbalRx?.stop()

        lastDroneCmd = zeroDroneCmd()
        sendVirtualStickNow(lastDroneCmd!!)

        vsEnabled = false

        vsManager.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.i(TAG, "Virtual Stick DISABLED")
            }

            override fun onFailure(error: IDJIError) {
                Log.w(TAG, "disableVirtualStick failed: ${error.description()}")
            }
        })
        Log.i(TAG, "CMD system STOPPED")
        showCmdLine("CMD system STOPPED")
        renderCmdUiState()

        if (moveGimbalToNeutral) {
            gimbalToNeutral()
        }
    }
}