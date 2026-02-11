package com.rice.solarismatrice

import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private val TAG = "MainActivity"

    private lateinit var videoTexture: TextureView
    private lateinit var btnVideo: Button
    private lateinit var btnTelem: Button

    private var surface: Surface? = null
    private var attached = false

    private var videoStreamer: ImageStreamer? = null
    private var telemetryStreamer: TelemetryStreamer? = null

    private var telemTimer: Timer? = null

    private var videoRunning = false
    private var telemRunning = false

    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN

    private val streamManager: ICameraStreamManager?
        get() = MediaDataCenter.getInstance().cameraStreamManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (AppPrefs.getPcIp(this).isNullOrBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        videoTexture = findViewById(R.id.videoTexture)
        btnVideo = findViewById(R.id.btnVideo)
        btnTelem = findViewById(R.id.btnTelem)

        videoTexture.surfaceTextureListener = this

        val ip = AppPrefs.getPcIp(this)!!
        videoStreamer = ImageStreamer(ip, AppPrefs.getVideoPort(this))
        telemetryStreamer = TelemetryStreamer(ip, AppPrefs.getTelemPort(this))

        btnVideo.setOnClickListener { toggleVideoStream() }
        btnTelem.setOnClickListener { toggleTelemetry() }
    }

    override fun onResume() {
        super.onResume()
        maybeAttachPreview()
    }

    override fun onPause() {
        // Stop stream verso PC (sia video che telem) quando l'app va in background.
        // La preview viene staccata per sicurezza.
        stopVideoStream()
        stopTelemetryIfNeeded()
        detachPreview()
        super.onPause()
    }

    /* ===================== PREVIEW DJI ===================== */

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

    /* ===================== VIDEO STREAM to PC ===================== */

    /* ===================== VIDEO STREAM to PC ===================== */

    /* ===================== VIDEO STREAM to PC ===================== */

    private var h264Listener: ICameraStreamManager.CameraFrameListener? = null

    private fun toggleVideoStream() {
        if (!videoRunning) {
            startVideoStream()
        } else {
            stopVideoStream()
        }
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

        val listener = ICameraStreamManager.CameraFrameListener { data, offset, length, width, height, format ->

            if (!videoRunning) return@CameraFrameListener
            if (length <= 0) return@CameraFrameListener

            // Non filtriamo più per format: la tua API non usa FrameFormat
            val frame = ByteArray(length)
            System.arraycopy(data, offset, frame, 0, length)

            videoStreamer?.sendRawYuv(frame, width, height)
        }

        h264Listener = listener

        // 👇 NELLA TUA VERSIONE È SOLO QUESTO
        mgr.addFrameListener(
            cameraIndex,
            ICameraStreamManager.FrameFormat.NV21,
            listener
        )

        videoRunning = true
        btnVideo.text = "STOP VIDEO"
        Log.i(TAG, "Video stream STARTED")
    }

    private fun stopVideoStream() {
        if (!videoRunning) return

        val mgr = streamManager

        h264Listener?.let { listener ->
            try {
                mgr?.removeFrameListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "removeFrameListener error", t)
            }
        }

        h264Listener = null
        videoStreamer?.stop()

        videoRunning = false
        btnVideo.text = "START VIDEO"
        Log.i(TAG, "Video stream STOPPED")
    }

    /* ===================== TELEMETRIA UDP ===================== */

    private fun toggleTelemetry() {
        if (!telemRunning) {
            startTelemetryIfNeeded()
        } else {
            stopTelemetryIfNeeded()
        }
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
        btnTelem.text = "STOP TELEMETRIA"
        Log.i(TAG, "Telemetry stream STARTED (UDP)")
    }

    private fun stopTelemetryIfNeeded() {
        if (!telemRunning) return

        telemTimer?.cancel()
        telemTimer = null

        telemetryStreamer?.stop()
        TelemetryProvider.stopIfNeeded()

        telemRunning = false
        btnTelem.text = "START TELEMETRIA"
        Log.i(TAG, "Telemetry stream STOPPED")
    }

    override fun onDestroy() {
        stopVideoStream()
        stopTelemetryIfNeeded()
        detachPreview()
        super.onDestroy()
    }
}