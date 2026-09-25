package com.rice.solarisbridge.v4.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v4.R
import com.rice.solarisbridge.v4.app.BridgeBootstrapV4
import com.rice.solarisbridge.v4.drone.control.CommandSystemController
import com.rice.solarisbridge.v4.drone.control.ControlCoordinator
import com.rice.solarisbridge.v4.drone.control.GimbalController
import com.rice.solarisbridge.v4.drone.control.WaypointMissionController
import com.rice.solarisbridge.v4.drone.telemetry.TelemetryController
import com.rice.solarisbridge.v4.drone.telemetry.VideoStreamController
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightAssistant
import dji.sdk.products.Aircraft

/**
 * Main screen of the V4 app: live preview plus start/stop controls for video, telemetry and PC
 * control. Builds and wires the controllers (gimbal, telemetry, video, command system, waypoint
 * mission) and the ControlCoordinator, and renders their state. The command button arms/disarms
 * the whole PC control surface (manual Virtual Stick + autonomous Waypoint Mission).
 */
class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private val TAG = "MainActivityV4"

    companion object {
        private const val MIN_SATELLITES_FOR_MISSION = 10

        // DEBUG ONLY — bench testing without GPS/flight.
        // MUST be false for real flights (true skips the satellite / is-flying checks; only for
        // bench tests with propellers removed). A valid aircraft position is required anyway.
        private const val DEV_BYPASS_GOTO_PRECONDITIONS = false
    }

    private lateinit var videoTexture: TextureView
    private lateinit var controlPanel: ScrollView

    private lateinit var btnVideo: MaterialButton
    private lateinit var btnTelemetry: MaterialButton
    private lateinit var tvVideoStatus: TextView
    private lateinit var tvTelemetryStatus: TextView

    private lateinit var btnCmd: MaterialButton
    private lateinit var tvCmdStatus: TextView
    private lateinit var tvCmdLast: TextView
    private lateinit var tvMissionStatus: TextView   // sticky mission outcome (not overwritten at 20 Hz)

    private lateinit var tvSatelliteCount: TextView

    private lateinit var btnObstacleAvoidance: MaterialButton
    private lateinit var tvObstacleAvoidanceStatus: TextView

    private var obstacleAvoidanceEnabled: Boolean? = null
    private var obstacleAvoidanceRequestInProgress = false
    private var obstacleAvoidanceRefreshInProgress = false

    private val obstacleAvoidanceRefreshHandler = Handler(Looper.getMainLooper())

    private val obstacleAvoidanceRefreshRunnable = object : Runnable {
        override fun run() {
            refreshObstacleAvoidanceState()
            obstacleAvoidanceRefreshHandler.postDelayed(this, 3000L)
        }
    }

    private var previewAttached = false

    private lateinit var gimbalController: GimbalController
    private lateinit var telemetryController: TelemetryController
    private lateinit var videoStreamController: VideoStreamController
    private lateinit var commandSystemController: CommandSystemController
    private lateinit var waypointMissionController: WaypointMissionController
    private lateinit var controlCoordinator: ControlCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        forwardUsbAttachToDji(intent)   // app launched by plugging in the RC

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        if (AppPrefs.getPcIp(this).isNullOrBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        bindViews()
        applyResponsiveLayout(resources.configuration.orientation)

        buildControllers()
        bindListeners()

        videoStreamController.rebuildFromPrefs()
        telemetryController.rebuildFromPrefs()
        commandSystemController.rebuildFromPrefs()
        waypointMissionController.rebuildFromPrefs()

        telemetryController.startMonitoring()

        renderUiState()
        startObstacleAvoidanceAutoRefresh()

        videoTexture.post {
            maybeAttachPreview()
        }
    }

    /**
     * launchMode="singleTask": an RC (re)attach or a launcher intent is delivered here to the EXISTING
     * instance instead of stacking a second MainActivity (two instances shared the single FC state
     * callback: destroying one froze the other's telemetry).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent action=${intent.action} (existing instance reused)")
        forwardUsbAttachToDji(intent)
    }

    /**
     * DJI MSDK V4: the SDK learns that the remote controller was (re)attached over USB (AOA) from
     * this broadcast, as in DJI's sample apps (DJIAoaControllerActivity). Needed when the activity
     * is already running and the RC is plugged in / reconnected, otherwise the SDK may not detect it.
     */
    private fun forwardUsbAttachToDji(intent: Intent?) {
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            Log.i(TAG, "USB accessory attached -> forwarding to DJI SDK")
            sendBroadcast(Intent(dji.sdk.sdkmanager.DJISDKManager.USB_ACCESSORY_ATTACHED))
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()

        videoStreamController.rebuildFromPrefs()
        telemetryController.rebuildFromPrefs()
        commandSystemController.rebuildFromPrefs()
        waypointMissionController.rebuildFromPrefs()

        telemetryController.startMonitoring()

        renderUiState()
        startObstacleAvoidanceAutoRefresh()

        videoTexture.post {
            maybeAttachPreview()
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause isChangingConfigurations=$isChangingConfigurations")

        if (!isChangingConfigurations) {
            stopObstacleAvoidanceAutoRefresh()
            val pausedDuringMission =
                ::waypointMissionController.isInitialized && waypointMissionController.isMissionActive
            if (pausedDuringMission) {
                Log.w(TAG, "onPause while a mission is active -> mission aborted")
            }
            if (::waypointMissionController.isInitialized) {
                waypointMissionController.abortMission()
                waypointMissionController.stopListening()
            }
            commandSystemController.stop(moveGimbalToNeutral = false)
            // Everything above is stopped, so the coordinator must not stay "armed": otherwise after
            // onResume the button shows armed while no receiver is listening (gotos silently lost).
            if (::controlCoordinator.isInitialized) {
                controlCoordinator.disarm()
            }
            if (pausedDuringMission) {
                // Posted after disarm() (same main looper) so this message is the one that stays.
                Handler(Looper.getMainLooper()).post {
                    showMissionStatus("ABORTED: app paused (onPause) during mission")
                }
            }
            videoStreamController.stop()
            telemetryController.stop()
        }

        detachPreview()
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy isChangingConfigurations=$isChangingConfigurations")

        if (!isChangingConfigurations) {
            stopObstacleAvoidanceAutoRefresh()

            if (::waypointMissionController.isInitialized) {
                waypointMissionController.abortMission()
                waypointMissionController.stopListening()
            }

            if (::commandSystemController.isInitialized) {
                commandSystemController.stop(moveGimbalToNeutral = false)
            }

            if (::videoStreamController.isInitialized) {
                videoStreamController.stop()
            }

            if (::telemetryController.isInitialized) {
                telemetryController.stop()
                telemetryController.stopMonitoring()
            }
        }

        detachPreview()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        Log.i(TAG, "onConfigurationChanged: orientation=${newConfig.orientation}")

        applyResponsiveLayout(newConfig.orientation)
        renderUiState()

        videoTexture.post {
            maybeAttachPreview()
        }
    }

    private fun bindViews() {
        videoTexture = findViewById(R.id.videoTexture)
        controlPanel = findViewById(R.id.controlPanel)

        btnVideo = findViewById(R.id.btnVideo)
        btnTelemetry = findViewById(R.id.btnTelemetry)
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        tvTelemetryStatus = findViewById(R.id.tvTelemetryStatus)

        tvSatelliteCount = findViewById(R.id.tvSatelliteCount)

        btnCmd = findViewById(R.id.btnCmd)
        tvCmdStatus = findViewById(R.id.tvCmdStatus)
        tvCmdLast = findViewById(R.id.tvCmdLast)
        tvMissionStatus = findViewById(R.id.tvMissionStatus)

        btnObstacleAvoidance = findViewById(R.id.btnObstacleAvoidance)
        tvObstacleAvoidanceStatus = findViewById(R.id.tvObstacleAvoidanceStatus)
    }

    private fun buildControllers() {
        gimbalController = GimbalController()

        telemetryController = TelemetryController(
            context = this,
            onSatelliteCountChanged = { count ->
                runOnUiThread {
                    tvSatelliteCount.text = if (count != null) {
                        getString(R.string.status_satellites_format, count)
                    } else {
                        getString(R.string.status_satellites_placeholder)
                    }
                }
            }
        )

        videoStreamController = VideoStreamController(this, videoTexture)

        commandSystemController = CommandSystemController(
            context = this,
            gimbalController = gimbalController,
            onStatusLine = { line -> showCmdLine(line) },
            onRunningChanged = {
                runOnUiThread { renderUiState() }
            },
            onManualOverride = { cmd -> controlCoordinator.onManualOverride(cmd) }
        )

        waypointMissionController = WaypointMissionController(
            context = this,
            currentPositionProvider = { telemetryController.currentPositionForMission() },
            onGotoReceived = { cmd -> controlCoordinator.onGotoReceived(cmd) },
            onMissionFinished = { controlCoordinator.onMissionFinished() },
            onMissionFailed = { reason, detail -> controlCoordinator.onMissionFailed(reason, detail) },
            onStatusLine = { line -> showCmdLine(line) },
            diagnostics = { telemetryController.missionDiagnostics() },
            onMissionProgress = { line -> showMissionStatus(line) }
        )

        controlCoordinator = ControlCoordinator(
            commandSystem = commandSystemController,
            waypointMission = waypointMissionController,
            onGotoState = { state -> telemetryController.setGotoState(state.name.lowercase()) },
            onArmedChanged = { runOnUiThread { renderUiState() } },
            onStatusLine = { line -> showCmdLine(line) },
            gpsHealthyProvider = {
                DEV_BYPASS_GOTO_PRECONDITIONS ||
                        (telemetryController.latestSatelliteCount() ?: 0) >= MIN_SATELLITES_FOR_MISSION
            },
            isFlyingProvider = {
                DEV_BYPASS_GOTO_PRECONDITIONS || telemetryController.isFlying()
            },
            positionValidProvider = { telemetryController.hasValidPosition() },
            onMissionStatus = { line -> showMissionStatus(line) },
            onGotoError = { err -> telemetryController.setGotoError(err) },
            diagnostics = { telemetryController.missionDiagnostics() }
        )
    }

    private fun bindListeners() {
        videoTexture.surfaceTextureListener = this

        btnVideo.setOnClickListener {
            Log.i(
                TAG,
                "btnVideo click: previewAttached=$previewAttached running=${videoStreamController.isRunning}"
            )

            if (!isDroneConnected()) return@setOnClickListener

            videoStreamController.toggle(previewAttached)
            renderUiState()
        }

        btnTelemetry.setOnClickListener {
            Log.i(TAG, "btnTelemetry click: running=${telemetryController.isRunning}")

            if (!isDroneConnected()) return@setOnClickListener

            telemetryController.toggle()
            renderUiState()
        }

        btnCmd.setOnClickListener {
            Log.i(TAG, "btnCmd click: armed=${controlCoordinator.isArmed}")

            if (!isDroneConnected()) return@setOnClickListener

            // Arms/disarms the whole PC control surface (manual + mission).
            controlCoordinator.toggleArmed()
            renderUiState()
        }

        btnObstacleAvoidance.setOnClickListener {
            Log.i(
                TAG,
                "btnObstacleAvoidance click: enabled=$obstacleAvoidanceEnabled inProgress=$obstacleAvoidanceRequestInProgress"
            )

            if (!isDroneConnected()) return@setOnClickListener
            if (obstacleAvoidanceRequestInProgress) return@setOnClickListener

            toggleObstacleAvoidance()
        }
    }

    private fun applyResponsiveLayout(orientation: Int) {
        val root = findViewById<ConstraintLayout>(R.id.rootLayout)
        val set = ConstraintSet()
        set.clone(root)

        set.clear(R.id.videoTexture)
        set.clear(R.id.controlPanel)

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            applyLandscapeConstraints(set)
        } else {
            applyPortraitConstraints(set)
        }

        set.applyTo(root)
    }

    private fun applyLandscapeConstraints(set: ConstraintSet) {
        set.constrainWidth(R.id.videoTexture, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(R.id.videoTexture, ConstraintSet.MATCH_CONSTRAINT)

        set.connect(
            R.id.videoTexture,
            ConstraintSet.TOP,
            R.id.topAppBar,
            ConstraintSet.BOTTOM
        )
        set.connect(
            R.id.videoTexture,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START
        )
        set.connect(
            R.id.videoTexture,
            ConstraintSet.END,
            R.id.controlPanel,
            ConstraintSet.START,
            dp(8)
        )
        set.connect(
            R.id.videoTexture,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM
        )

        set.constrainWidth(R.id.controlPanel, dp(270))
        set.constrainHeight(R.id.controlPanel, ConstraintSet.MATCH_CONSTRAINT)

        set.connect(
            R.id.controlPanel,
            ConstraintSet.TOP,
            R.id.topAppBar,
            ConstraintSet.BOTTOM
        )
        set.connect(
            R.id.controlPanel,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END,
            dp(8)
        )
        set.connect(
            R.id.controlPanel,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM
        )

        controlPanel.setPadding(0, dp(8), dp(8), dp(8))
    }

    private fun applyPortraitConstraints(set: ConstraintSet) {
        set.constrainWidth(R.id.videoTexture, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(R.id.videoTexture, dp(220))

        set.connect(
            R.id.videoTexture,
            ConstraintSet.TOP,
            R.id.topAppBar,
            ConstraintSet.BOTTOM,
            dp(8)
        )
        set.connect(
            R.id.videoTexture,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START,
            dp(8)
        )
        set.connect(
            R.id.videoTexture,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END,
            dp(8)
        )

        set.constrainWidth(R.id.controlPanel, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(R.id.controlPanel, ConstraintSet.MATCH_CONSTRAINT)

        set.connect(
            R.id.controlPanel,
            ConstraintSet.TOP,
            R.id.videoTexture,
            ConstraintSet.BOTTOM,
            dp(8)
        )
        set.connect(
            R.id.controlPanel,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START,
            dp(12)
        )
        set.connect(
            R.id.controlPanel,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END,
            dp(12)
        )
        set.connect(
            R.id.controlPanel,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM,
            dp(12)
        )

        controlPanel.setPadding(0, 0, 0, dp(12))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun isDroneConnected(): Boolean {
        val product = BridgeBootstrapV4.getProductInstance()
        val aircraft = product as? Aircraft

        Log.i(
            TAG,
            """
            isDroneConnected:
            sdkRegistered=${BridgeBootstrapV4.isSdkRegistered()}
            product=$product
            productConnected=${product?.isConnected}
            model=${product?.model}
            isAircraft=${aircraft != null}
            camera=${product?.camera}
            gimbal=${product?.gimbal}
            flightController=${aircraft?.flightController}
            flightAssistant=${aircraft?.flightController?.flightAssistant}
            """.trimIndent()
        )

        if (product == null || !product.isConnected) {
            Log.w(TAG, "Controller/drone non connesso")
            Toast.makeText(
                this,
                getString(R.string.toast_controller_drone_not_connected),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        return true
    }

    private fun flightAssistant(): FlightAssistant? {
        val aircraft = BridgeBootstrapV4.getProductInstance() as? Aircraft
        return aircraft?.flightController?.flightAssistant
    }

    private fun startObstacleAvoidanceAutoRefresh() {
        obstacleAvoidanceRefreshHandler.removeCallbacks(obstacleAvoidanceRefreshRunnable)
        obstacleAvoidanceRefreshRunnable.run()
    }

    private fun stopObstacleAvoidanceAutoRefresh() {
        obstacleAvoidanceRefreshHandler.removeCallbacks(obstacleAvoidanceRefreshRunnable)
        obstacleAvoidanceRefreshInProgress = false
    }

    private fun refreshObstacleAvoidanceState() {
        if (obstacleAvoidanceRequestInProgress) return
        if (obstacleAvoidanceRefreshInProgress) return

        val assistant = flightAssistant()

        if (assistant == null) {
            obstacleAvoidanceEnabled = null
            renderObstacleAvoidanceState()
            Log.w(TAG, "refreshObstacleAvoidanceState: FlightAssistant null")
            return
        }

        obstacleAvoidanceRefreshInProgress = true

        try {
            assistant.getCollisionAvoidanceEnabled(
                object : CommonCallbacks.CompletionCallbackWith<Boolean> {
                    override fun onSuccess(enabled: Boolean?) {
                        Log.i(TAG, "Obstacle avoidance state: $enabled")

                        obstacleAvoidanceRefreshInProgress = false
                        obstacleAvoidanceEnabled = enabled

                        runOnUiThread {
                            renderObstacleAvoidanceState()
                        }
                    }

                    override fun onFailure(error: DJIError?) {
                        Log.w(
                            TAG,
                            "getCollisionAvoidanceEnabled failed: ${error?.description}"
                        )

                        obstacleAvoidanceRefreshInProgress = false
                        obstacleAvoidanceEnabled = null

                        runOnUiThread {
                            renderObstacleAvoidanceState()
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "refreshObstacleAvoidanceState exception", t)

            obstacleAvoidanceRefreshInProgress = false
            obstacleAvoidanceEnabled = null

            runOnUiThread {
                renderObstacleAvoidanceState()
            }
        }
    }

    private fun toggleObstacleAvoidance() {
        val current = obstacleAvoidanceEnabled ?: return
        setObstacleAvoidanceEnabled(!current)
    }

    private fun setObstacleAvoidanceEnabled(enabled: Boolean) {
        val assistant = flightAssistant()

        if (assistant == null) {
            obstacleAvoidanceEnabled = null
            renderObstacleAvoidanceState()
            Toast.makeText(
                this,
                getString(R.string.toast_obstacle_avoidance_not_available),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        obstacleAvoidanceRequestInProgress = true
        renderObstacleAvoidanceState()

        try {
            assistant.setCollisionAvoidanceEnabled(
                enabled,
                object : CommonCallbacks.CompletionCallback<DJIError> {
                    override fun onResult(error: DJIError?) {
                        obstacleAvoidanceRequestInProgress = false

                        if (error != null) {
                            Log.w(
                                TAG,
                                "setCollisionAvoidanceEnabled($enabled) failed: ${error.description}"
                            )

                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(
                                        R.string.toast_obstacle_avoidance_not_modified_format,
                                        error.description
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()

                                refreshObstacleAvoidanceState()
                            }

                            return
                        }

                        obstacleAvoidanceEnabled = enabled

                        Log.i(TAG, "Obstacle avoidance set to $enabled")

                        runOnUiThread {
                            renderObstacleAvoidanceState()

                            Toast.makeText(
                                this@MainActivity,
                                if (enabled) {
                                    getString(R.string.toast_obstacle_avoidance_enabled)
                                } else {
                                    getString(R.string.toast_obstacle_avoidance_disabled)
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            obstacleAvoidanceRequestInProgress = false

            Log.e(TAG, "setObstacleAvoidanceEnabled exception", t)

            runOnUiThread {
                renderObstacleAvoidanceState()
                Toast.makeText(
                    this,
                    getString(R.string.toast_obstacle_avoidance_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun renderObstacleAvoidanceState() {
        val green = ColorStateList.valueOf(getColor(R.color.state_green))
        val red = ColorStateList.valueOf(getColor(R.color.state_red))
        val gray = ColorStateList.valueOf(getColor(android.R.color.darker_gray))

        if (obstacleAvoidanceRequestInProgress) {
            btnObstacleAvoidance.isEnabled = false
            btnObstacleAvoidance.text = getString(R.string.button_updating_obstacle_avoidance)
            btnObstacleAvoidance.backgroundTintList = gray

            tvObstacleAvoidanceStatus.text = getString(R.string.status_obstacle_avoidance_updating)
            tvObstacleAvoidanceStatus.setTextColor(getColor(android.R.color.darker_gray))
            return
        }

        when (obstacleAvoidanceEnabled) {
            true -> {
                btnObstacleAvoidance.isEnabled = true
                btnObstacleAvoidance.text = getString(R.string.button_disable_obstacle_avoidance)
                btnObstacleAvoidance.backgroundTintList = red

                tvObstacleAvoidanceStatus.text = getString(R.string.status_obstacle_avoidance_on)
                tvObstacleAvoidanceStatus.setTextColor(getColor(R.color.state_green))
            }

            false -> {
                btnObstacleAvoidance.isEnabled = true
                btnObstacleAvoidance.text = getString(R.string.button_enable_obstacle_avoidance)
                btnObstacleAvoidance.backgroundTintList = green

                tvObstacleAvoidanceStatus.text = getString(R.string.status_obstacle_avoidance_off)
                tvObstacleAvoidanceStatus.setTextColor(getColor(R.color.state_red))
            }

            null -> {
                btnObstacleAvoidance.isEnabled = false
                btnObstacleAvoidance.text = getString(R.string.button_obstacle_avoidance_unavailable)
                btnObstacleAvoidance.backgroundTintList = gray

                tvObstacleAvoidanceStatus.text = getString(R.string.status_obstacle_avoidance_unknown)
                tvObstacleAvoidanceStatus.setTextColor(getColor(android.R.color.darker_gray))
            }
        }
    }

    private fun showCmdLine(line: String) {
        runOnUiThread {
            tvCmdLast.text = getString(R.string.status_last_command_format, line)
        }
    }

    /**
     * Sticky mission outcome (STARTING / ARRIVED / FAILED: reason / REJECTED: reason / ...).
     * Unlike tvCmdLast it is not overwritten by the 20 Hz flight/gimbal lines, so the failure
     * reason stays readable (and can be photographed) on the phone after a field test.
     */
    private fun showMissionStatus(line: String) {
        Log.i(TAG, "MISSION STATUS: $line")
        runOnUiThread {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            tvMissionStatus.text = getString(R.string.status_mission_format, time, line)
            val color = when {
                line.startsWith("ARRIVED") -> getColor(R.color.state_green)
                line.startsWith("FAILED") || line.startsWith("REJECTED") ||
                        line.startsWith("ABORTED") || line.startsWith("OVERRIDE") -> getColor(R.color.state_red)
                else -> tvCmdLast.currentTextColor
            }
            tvMissionStatus.setTextColor(color)
        }
    }

    private fun renderUiState() {
        val green = ColorStateList.valueOf(getColor(R.color.state_green))
        val red = ColorStateList.valueOf(getColor(R.color.state_red))

        if (videoStreamController.isRunning) {
            btnVideo.text = getString(R.string.button_stop_video_stream)
            btnVideo.backgroundTintList = red
            tvVideoStatus.text = getString(R.string.status_video_stream_on)
            tvVideoStatus.setTextColor(getColor(R.color.state_green))
        } else {
            btnVideo.text = getString(R.string.button_start_video_stream)
            btnVideo.backgroundTintList = green
            tvVideoStatus.text = getString(R.string.status_video_stream_off)
            tvVideoStatus.setTextColor(getColor(R.color.state_red))
        }

        if (telemetryController.isRunning) {
            btnTelemetry.text = getString(R.string.button_stop_telemetry_stream)
            btnTelemetry.backgroundTintList = red
            tvTelemetryStatus.text = getString(R.string.status_telemetry_stream_on)
            tvTelemetryStatus.setTextColor(getColor(R.color.state_green))
        } else {
            btnTelemetry.text = getString(R.string.button_start_telemetry_stream)
            btnTelemetry.backgroundTintList = green
            tvTelemetryStatus.text = getString(R.string.status_telemetry_stream_off)
            tvTelemetryStatus.setTextColor(getColor(R.color.state_red))
        }

        renderCmdUiState()
        renderObstacleAvoidanceState()
    }

    private fun renderCmdUiState() {
        val green = ColorStateList.valueOf(getColor(R.color.state_green))
        val red = ColorStateList.valueOf(getColor(R.color.state_red))

        // "Armed" = PC control active, both in manual (Virtual Stick) and in MISSION.
        val pcControlArmed = ::controlCoordinator.isInitialized && controlCoordinator.isArmed

        if (pcControlArmed) {
            btnCmd.text = getString(R.string.button_stop_command_receiver)
            btnCmd.backgroundTintList = red
            tvCmdStatus.text = getString(R.string.status_command_system_on)
            tvCmdStatus.setTextColor(getColor(R.color.state_green))
        } else {
            btnCmd.text = getString(R.string.button_start_command_receiver)
            btnCmd.backgroundTintList = green
            tvCmdStatus.text = getString(R.string.status_command_system_off)
            tvCmdStatus.setTextColor(getColor(R.color.state_red))
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        Log.i(TAG, "onSurfaceTextureAvailable: ${w}x$h")

        videoStreamController.onSurfaceAvailable(st, w, h)
        updatePreviewTransform(w, h)
        attachPreview(w, h)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        Log.i(TAG, "onSurfaceTextureSizeChanged: ${w}x$h")

        detachPreview()
        videoStreamController.onSurfaceSizeChanged(st, w, h)
        updatePreviewTransform(w, h)
        attachPreview(w, h)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        Log.i(TAG, "onSurfaceTextureDestroyed")

        detachPreview()
        videoStreamController.onSurfaceDestroyed()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun maybeAttachPreview() {
        Log.i(
            TAG,
            "maybeAttachPreview: texture=${videoTexture.width}x${videoTexture.height}, " +
                    "surfaceTexture=${videoTexture.surfaceTexture != null}, previewAttached=$previewAttached"
        )

        val st = videoTexture.surfaceTexture ?: return

        videoStreamController.onSurfaceAvailable(st, videoTexture.width, videoTexture.height)
        updatePreviewTransform(videoTexture.width, videoTexture.height)
        attachPreview(videoTexture.width, videoTexture.height)
    }

    private fun updatePreviewTransform(videoW: Int, videoH: Int) {
        val viewW = videoTexture.width.toFloat()
        val viewH = videoTexture.height.toFloat()

        if (viewW <= 0f || viewH <= 0f || videoW <= 0 || videoH <= 0) return

        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val viewAspect = viewW / viewH

        val matrix = Matrix()

        val scaleX: Float
        val scaleY: Float

        if (videoAspect > viewAspect) {
            scaleX = 1f
            scaleY = viewAspect / videoAspect
        } else {
            scaleX = videoAspect / viewAspect
            scaleY = 1f
        }

        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        videoTexture.setTransform(matrix)
    }

    private fun attachPreview(w: Int, h: Int) {
        Log.i(TAG, "attachPreview chiamata: w=$w h=$h previewAttached=$previewAttached")

        if (previewAttached) {
            Log.i(TAG, "attachPreview: preview gia attaccata, skip")
            return
        }

        if (w <= 0 || h <= 0) {
            Log.w(TAG, "attachPreview: dimensioni non valide ${w}x$h")
            return
        }

        previewAttached = true
        Log.i(TAG, "Preview ATTACCATA: size=${w}x$h")
    }

    private fun detachPreview() {
        Log.i(TAG, "detachPreview chiamata: previewAttached=$previewAttached")

        if (!previewAttached) {
            Log.i(TAG, "detachPreview: preview non attaccata, skip")
            return
        }

        previewAttached = false
        Log.i(TAG, "Preview STACCATA")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.app_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            R.id.menu_main -> true

            else -> super.onOptionsItemSelected(item)
        }
    }
}