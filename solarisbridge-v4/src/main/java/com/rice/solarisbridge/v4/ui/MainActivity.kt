package com.rice.solarisbridge.v4.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.rice.solarisbridge.common.contracts.BridgeCommandController
import com.rice.solarisbridge.common.contracts.BridgeTelemetryController
import com.rice.solarisbridge.common.contracts.BridgeVideoController
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v4.R
import com.rice.solarisbridge.v4.drone.control.CommandSystemController
import com.rice.solarisbridge.v4.drone.control.GimbalController
import com.rice.solarisbridge.v4.drone.telemetry.VideoStreamController
import com.rice.solarisbridge.v4.drone.telemetry.TelemetryController

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

    private var surface: Surface? = null
    private var previewAttached = false

    private lateinit var gimbalController: GimbalController
    private lateinit var telemetryController: BridgeTelemetryController
    private lateinit var videoStreamController: BridgeVideoController
    private lateinit var commandSystemController: BridgeCommandController

    override fun onCreate(savedInstanceState: Bundle?) {
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
        super.onResume()

        videoStreamController.rebuildFromPrefs()
        telemetryController.rebuildFromPrefs()
        commandSystemController.rebuildFromPrefs()

        renderUiState()
        maybeAttachPreview()
    }

    override fun onPause() {
        commandSystemController.stop(moveGimbalToNeutral = false)
        videoStreamController.stop()
        telemetryController.stop()
        detachPreview()
        super.onPause()
    }

    override fun onDestroy() {
        commandSystemController.stop(moveGimbalToNeutral = false)
        videoStreamController.stop()
        telemetryController.stop()
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
        videoStreamController = VideoStreamController(this)
        commandSystemController = CommandSystemController(
            context = this,
            gimbalController = gimbalController,
            onStatusLine = { line -> showCmdLine(line) }
        )
    }

    private fun bindListeners() {
        videoTexture.surfaceTextureListener = this

        btnVideo.setOnClickListener {
            videoStreamController.toggle(previewAttached)
            renderUiState()
        }

        btnTelemetry.setOnClickListener {
            telemetryController.toggle()
            renderUiState()
        }

        btnCmd.setOnClickListener {
            commandSystemController.toggle()
            renderUiState()
        }
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
        surface = Surface(st)
        previewAttached = w > 0 && h > 0
        Log.i(tag, "Surface available: ${w}x$h")
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        previewAttached = w > 0 && h > 0
        Log.i(tag, "Surface size changed: ${w}x$h")
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        detachPreview()
        surface = null
        previewAttached = false
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun maybeAttachPreview() {
        val st = videoTexture.surfaceTexture ?: return
        if (surface == null) surface = Surface(st)
        previewAttached = videoTexture.width > 0 && videoTexture.height > 0
    }

    private fun detachPreview() {
        previewAttached = false
        Log.i(tag, "Preview detached")
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