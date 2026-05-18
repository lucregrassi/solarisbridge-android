package com.rice.solarisbridge.v4.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
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
import com.rice.solarisbridge.v4.drone.control.GimbalController
import com.rice.solarisbridge.v4.drone.telemetry.TelemetryController
import com.rice.solarisbridge.v4.drone.telemetry.VideoStreamController
import dji.sdk.products.Aircraft

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private val TAG = "MainActivityV4"

    private lateinit var videoTexture: TextureView
    private lateinit var controlPanel: ScrollView

    private lateinit var btnVideo: MaterialButton
    private lateinit var btnTelemetry: MaterialButton
    private lateinit var tvVideoStatus: TextView
    private lateinit var tvTelemetryStatus: TextView

    private lateinit var btnCmd: MaterialButton
    private lateinit var tvCmdStatus: TextView
    private lateinit var tvCmdLast: TextView

    private lateinit var tvSatelliteCount: TextView

    private var previewAttached = false

    private lateinit var gimbalController: GimbalController
    private lateinit var telemetryController: TelemetryController
    private lateinit var videoStreamController: VideoStreamController
    private lateinit var commandSystemController: CommandSystemController

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        telemetryController.startMonitoring()

        renderUiState()

        videoTexture.post {
            maybeAttachPreview()
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()

        videoStreamController.rebuildFromPrefs()
        telemetryController.rebuildFromPrefs()
        commandSystemController.rebuildFromPrefs()

        telemetryController.startMonitoring()

        renderUiState()

        videoTexture.post {
            maybeAttachPreview()
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause isChangingConfigurations=$isChangingConfigurations")

        if (!isChangingConfigurations) {
            commandSystemController.stop(moveGimbalToNeutral = false)
            videoStreamController.stop()
            telemetryController.stop()
        }

        detachPreview()
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy isChangingConfigurations=$isChangingConfigurations")

        if (!isChangingConfigurations) {
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
    }

    private fun buildControllers() {
        gimbalController = GimbalController()

        telemetryController = TelemetryController(
            context = this,
            onSatelliteCountChanged = { count ->
                runOnUiThread {
                    tvSatelliteCount.text = if (count != null) {
                        getString(R.string.satellite_count_format, count)
                    } else {
                        getString(R.string.satellite_count_placeholder)
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
            }
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
            Log.i(TAG, "btnCmd click: running=${commandSystemController.isRunning}")

            if (!isDroneConnected()) return@setOnClickListener

            commandSystemController.toggle()
            renderUiState()
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
            """.trimIndent()
        )

        if (product == null || !product.isConnected) {
            Log.w(TAG, "Controller/drone non connesso")
            Toast.makeText(this, "Controller/drone non connesso", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    private fun showCmdLine(line: String) {
        runOnUiThread {
            tvCmdLast.text = getString(R.string.last_command_format, line)
        }
    }

    private fun renderUiState() {
        val green = ColorStateList.valueOf(getColor(R.color.state_green))
        val red = ColorStateList.valueOf(getColor(R.color.state_red))

        if (videoStreamController.isRunning) {
            btnVideo.text = getString(R.string.stop_video_stream)
            btnVideo.backgroundTintList = red
            tvVideoStatus.text = getString(R.string.video_stream_on)
            tvVideoStatus.setTextColor(getColor(R.color.state_green))
        } else {
            btnVideo.text = getString(R.string.start_video_stream)
            btnVideo.backgroundTintList = green
            tvVideoStatus.text = getString(R.string.video_stream_off)
            tvVideoStatus.setTextColor(getColor(R.color.state_red))
        }

        if (telemetryController.isRunning) {
            btnTelemetry.text = getString(R.string.stop_telemetry_stream)
            btnTelemetry.backgroundTintList = red
            tvTelemetryStatus.text = getString(R.string.telemetry_stream_on)
            tvTelemetryStatus.setTextColor(getColor(R.color.state_green))
        } else {
            btnTelemetry.text = getString(R.string.start_telemetry_stream)
            btnTelemetry.backgroundTintList = green
            tvTelemetryStatus.text = getString(R.string.telemetry_stream_off)
            tvTelemetryStatus.setTextColor(getColor(R.color.state_red))
        }

        renderCmdUiState()
    }

    private fun renderCmdUiState() {
        val green = ColorStateList.valueOf(getColor(R.color.state_green))
        val red = ColorStateList.valueOf(getColor(R.color.state_red))

        if (commandSystemController.isRunning) {
            btnCmd.text = getString(R.string.stop_command_receiver)
            btnCmd.backgroundTintList = red
            tvCmdStatus.text = getString(R.string.command_system_on)
            tvCmdStatus.setTextColor(getColor(R.color.state_green))
        } else {
            btnCmd.text = getString(R.string.start_command_receiver)
            btnCmd.backgroundTintList = green
            tvCmdStatus.text = getString(R.string.command_system_off)
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