package com.rice.solarisbridge.v4.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v4.R
import com.rice.solarisbridge.v4.app.BridgeAppV4
import com.rice.solarisbridge.v4.drone.control.CommandSystemController
import com.rice.solarisbridge.v4.drone.control.GimbalController
import com.rice.solarisbridge.v4.drone.telemetry.TelemetryController
import com.rice.solarisbridge.v4.drone.telemetry.VideoStreamController

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private val tag = "MainActivityV4"

    private lateinit var videoTexture: TextureView
    private lateinit var btnVideo: MaterialButton
    private lateinit var btnTelemetry: MaterialButton
    private lateinit var tvVideoStatus: TextView
    private lateinit var tvTelemetryStatus: TextView

    private lateinit var btnCmd: MaterialButton
    private lateinit var tvCmdStatus: TextView
    private lateinit var tvCmdLast: TextView

    private var previewAttached = false

    private lateinit var gimbalController: GimbalController
    private lateinit var telemetryController: TelemetryController
    private lateinit var videoStreamController: VideoStreamController
    private lateinit var commandSystemController: CommandSystemController

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(tag, "onCreate")

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
        buildControllers()
        bindListeners()

        videoStreamController.rebuildFromPrefs()
        telemetryController.rebuildFromPrefs()
        commandSystemController.rebuildFromPrefs()

        renderUiState()
    }

    override fun onResume() {
        Log.i(tag, "onResume")
        super.onResume()

        videoStreamController.rebuildFromPrefs()
        telemetryController.rebuildFromPrefs()
        commandSystemController.rebuildFromPrefs()

        renderUiState()
        maybeAttachPreview()
    }

    override fun onPause() {
        Log.i(tag, "onPause")
        commandSystemController.stop(moveGimbalToNeutral = false)
        videoStreamController.stop()
        telemetryController.stop()
        detachPreview()
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(tag, "onDestroy")

        if (::commandSystemController.isInitialized) {
            commandSystemController.stop(moveGimbalToNeutral = false)
        }
        if (::videoStreamController.isInitialized) {
            videoStreamController.stop()
        }
        if (::telemetryController.isInitialized) {
            telemetryController.stop()
        }

        detachPreview()
        super.onDestroy()
    }

    private fun bindViews() {
        videoTexture = findViewById(R.id.videoTexture)
        btnVideo = findViewById(R.id.btnVideo)
        btnTelemetry = findViewById(R.id.btnTelemetry)
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        tvTelemetryStatus = findViewById(R.id.tvTelemetryStatus)
        btnCmd = findViewById(R.id.btnCmd)
        tvCmdStatus = findViewById(R.id.tvCmdStatus)
        tvCmdLast = findViewById(R.id.tvCmdLast)
    }

    private fun buildControllers() {
        gimbalController = GimbalController()
        telemetryController = TelemetryController(this)
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
            Log.i(tag, "btnVideo click: previewAttached=$previewAttached running=${videoStreamController.isRunning}")
            if (!isDroneConnected()) return@setOnClickListener
            videoStreamController.toggle(previewAttached)
            renderUiState()
        }

        btnTelemetry.setOnClickListener {
            Log.i(tag, "btnTelemetry click: running=${telemetryController.isRunning}")
            if (!isDroneConnected()) return@setOnClickListener
            telemetryController.toggle()
            renderUiState()
        }

        btnCmd.setOnClickListener {
            Log.i(tag, "btnCmd click: running=${commandSystemController.isRunning}")
            if (!isDroneConnected()) return@setOnClickListener
            commandSystemController.toggle()
            renderUiState()
        }
    }

    private fun isDroneConnected(showToast: Boolean = true): Boolean {
        val connected = BridgeAppV4.getProductInstance() != null
        if (!connected) {
            Log.w(tag, "Controller/drone non connesso")
            if (showToast) {
                Toast.makeText(this, "Controller o drone non connesso", Toast.LENGTH_SHORT).show()
            }
        }
        return connected
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
        Log.i(tag, "onSurfaceTextureAvailable: ${w}x$h")
        videoStreamController.onSurfaceAvailable(st, w, h)
        attachPreview(w, h)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        Log.i(tag, "onSurfaceTextureSizeChanged: ${w}x$h")
        detachPreview()
        videoStreamController.onSurfaceSizeChanged(st, w, h)
        attachPreview(w, h)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        Log.i(tag, "onSurfaceTextureDestroyed")
        detachPreview()
        videoStreamController.onSurfaceDestroyed()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun maybeAttachPreview() {
        Log.i(
            tag,
            "maybeAttachPreview: texture=${videoTexture.width}x${videoTexture.height}, " +
                    "surfaceTexture=${videoTexture.surfaceTexture != null}, previewAttached=$previewAttached"
        )

        val st = videoTexture.surfaceTexture ?: return
        videoStreamController.onSurfaceAvailable(st, videoTexture.width, videoTexture.height)
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
        Log.i(tag, "attachPreview chiamata: w=$w h=$h previewAttached=$previewAttached")

        if (previewAttached) {
            Log.i(tag, "attachPreview: preview gia attaccata, skip")
            return
        }

        if (w <= 0 || h <= 0) {
            Log.w(tag, "attachPreview: dimensioni non valide ${w}x$h")
            return
        }

        previewAttached = true
        Log.i(tag, "Preview ATTACCATA: size=${w}x$h")
    }

    private fun detachPreview() {
        Log.i(tag, "detachPreview chiamata: previewAttached=$previewAttached")

        if (!previewAttached) {
            Log.i(tag, "detachPreview: preview non attaccata, skip")
            return
        }

        previewAttached = false
        Log.i(tag, "Preview STACCATA")
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