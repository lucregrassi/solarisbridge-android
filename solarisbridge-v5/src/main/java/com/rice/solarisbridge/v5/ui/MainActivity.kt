package com.rice.solarisbridge.v5.ui

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
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v5.drone.control.CommandSystemController
import com.rice.solarisbridge.v5.drone.control.GimbalController
import com.rice.solarisbridge.v5.R
import com.rice.solarisbridge.v5.drone.telemetry.TelemetryController
import com.rice.solarisbridge.v5.drone.telemetry.VideoStreamController
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.manager.interfaces.SDKManagerCallback

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private val tag = "MainActivity"

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
    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN

    private val streamManager: ICameraStreamManager?
        get() = MediaDataCenter.getInstance().cameraStreamManager

    private lateinit var gimbalController: GimbalController
    private lateinit var telemetryController: TelemetryController
    private lateinit var videoStreamController: VideoStreamController
    private lateinit var commandSystemController: CommandSystemController

    private val sdkCallback: SDKManagerCallback = object : SDKManagerCallback {
        override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
            if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                SDKManager.getInstance().registerApp()
            }
        }

        override fun onRegisterSuccess() {
            Log.i(tag, "onRegisterSuccess")
            runOnUiThread {
                detachPreview()
                maybeAttachPreview()
            }
        }

        override fun onRegisterFailure(error: IDJIError?) {
            Log.e("MainActivity", "onRegisterFailure: ${error?.description()}")
        }

        override fun onDatabaseDownloadProgress(current: Long, total: Long) {
            val progress = if (total > 0) (100 * current / total) else 0
            Log.i("MainActivity", "DB progress: $progress%")
        }

        override fun onProductConnect(productId: Int) {
            Log.i(tag, "onProductConnect: productId=$productId")
        }

        override fun onProductDisconnect(productId: Int) {
            Log.i(tag, "onProductDisconnect: productId=$productId")
        }

        override fun onProductChanged(productId: Int) {
            Log.i(tag, "onProductChanged: productId=$productId")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(tag, "onCreate")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SDKManager.getInstance().init(applicationContext, sdkCallback)

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
        videoStreamController = VideoStreamController(this, cameraIndex)
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
        Log.i(tag, "onSurfaceTextureAvailable: ${w}x$h")
        surface = Surface(st)
        attachPreview(w, h)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        Log.i(tag, "onSurfaceTextureSizeChanged: ${w}x$h")
        detachPreview()
        surface = Surface(st)
        attachPreview(w, h)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        Log.i(tag, "onSurfaceTextureDestroyed")
        detachPreview()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun maybeAttachPreview() {
        Log.i(
            tag,
            "maybeAttachPreview: texture=${videoTexture.width}x${videoTexture.height}, " +
                    "surfaceTexture=${videoTexture.surfaceTexture != null}, surface=${surface != null}, previewAttached=$previewAttached"
        )
        val st = videoTexture.surfaceTexture ?: return
        if (surface == null) surface = Surface(st)
        attachPreview(videoTexture.width, videoTexture.height)
    }

    private fun attachPreview(w: Int, h: Int) {
        Log.i(
            tag,
            "attachPreview chiamata: w=$w h=$h surface=${surface != null} previewAttached=$previewAttached cameraIndex=$cameraIndex"
        )
        val s = surface ?: return
        val mgr = streamManager ?: run {
            Log.w(tag, "CameraStreamManager non disponibile")
            return
        }
        if (previewAttached || w <= 0 || h <= 0) return

        mgr.putCameraStreamSurface(
            cameraIndex,
            s,
            w,
            h,
            ICameraStreamManager.ScaleType.CENTER_INSIDE
        )
        previewAttached = true
        Log.i(tag, "Preview ATTACCATA: index=$cameraIndex size=${w}x$h")
    }

    private fun detachPreview() {
        Log.i(tag, "detachPreview chiamata: surface=${surface != null} previewAttached=$previewAttached")
        val s = surface ?: return
        if (!previewAttached) return
        try {
            streamManager?.removeCameraStreamSurface(s)
        } catch (_: Throwable) {
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