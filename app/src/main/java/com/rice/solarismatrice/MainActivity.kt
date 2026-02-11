package com.rice.solarismatrice

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.sdk.keyvalue.value.common.ComponentIndexType

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private val TAG = "MainActivity"

    private lateinit var videoTexture: TextureView
    private lateinit var btnVideo: Button
    private lateinit var btnTelem: Button

    private var surface: Surface? = null
    private var attached = false

    private var videoStreamer: ImageStreamer? = null
    private var telemetryStreamer: TelemetryStreamer? = null

    private var videoTimer: Timer? = null
    private var telemTimer: Timer? = null

    private var videoRunning = false
    private var telemRunning = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraIndex = ComponentIndexType.LEFT_OR_MAIN

    private val streamManager: ICameraStreamManager?
        get() = MediaDataCenter.getInstance().cameraStreamManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Safety: se non configurato, torna alle settings
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

        // Avvia listener DJI (telemetria reale)
        TelemetryProvider.start()
        telemTimer = fixedRateTimer("telem", false, 0L, 100L) {
            telemetryStreamer?.sendCurrentSnapshot(TelemetryProvider.getSnapshot())
        }

        btnVideo.setOnClickListener { toggleVideo() }
        btnTelem.setOnClickListener { toggleTelemetry() }
    }

    override fun onResume() {
        super.onResume()
        // Se la TextureView è già pronta, prova ad attaccare preview
        maybeAttachPreview()
    }

    override fun onPause() {
        // In pausa: stacca preview e ferma eventuali stream (più sicuro su RC)
        stopVideoLoop()
        stopTelemetryLoop()
        videoStreamer?.stop()
        telemetryStreamer?.stop()
        videoRunning = false
        telemRunning = false
        btnVideo.text = "START VIDEO"
        btnTelem.text = "START TELEMETRIA"

        detachPreview()
        super.onPause()
    }

    /* ===================== VIDEO PREVIEW DJI ===================== */

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
        if (attached) return
        if (w <= 0 || h <= 0) return

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
        try {
            streamManager?.removeCameraStreamSurface(s)
        } catch (_: Throwable) {
        }
        attached = false
        Log.i(TAG, "Preview STACCATA")
    }

    /* ===================== VIDEO STREAM TCP ===================== */

    private fun toggleVideo() {
        if (!videoRunning) {
            videoStreamer?.start()
            startVideoLoop()
            btnVideo.text = "STOP VIDEO"
            videoRunning = true
        } else {
            stopVideoLoop()
            videoStreamer?.stop()
            btnVideo.text = "START VIDEO"
            videoRunning = false
        }
    }

    private fun startVideoLoop() {
        stopVideoLoop()

        // 10 fps (100ms). Alza a 40ms (~25fps) solo se regge CPU/rete.
        videoTimer = fixedRateTimer(name = "video", daemon = true, initialDelay = 0L, period = 100L) {
            // Cattura bitmap SEMPRE sul main thread, più stabile
            mainHandler.post {
                val jpeg = captureFrameJpeg(quality = 70) ?: return@post
                videoStreamer?.sendJpeg(jpeg)
            }
        }
    }

    private fun stopVideoLoop() {
        videoTimer?.cancel()
        videoTimer = null
    }

    private fun captureFrameJpeg(quality: Int): ByteArray? {
        val bmp: Bitmap = videoTexture.bitmap ?: return null
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 95), baos)
        return baos.toByteArray()
    }

    /* ===================== TELEMETRIA UDP (REALE) ===================== */

    private fun toggleTelemetry() {
        if (!telemRunning) {
            telemetryStreamer?.start()
            startTelemetryLoop()
            btnTelem.text = "STOP TELEMETRIA"
            telemRunning = true
        } else {
            stopTelemetryLoop()
            telemetryStreamer?.stop()
            btnTelem.text = "START TELEMETRIA"
            telemRunning = false
        }
    }

    private fun startTelemetryLoop() {
        stopTelemetryLoop()

        // 10 Hz = 100ms
        telemTimer = fixedRateTimer(name = "telem", daemon = true, initialDelay = 0L, period = 100L) {
            val snap = TelemetryProvider.getSnapshot()
            telemetryStreamer?.sendCurrentSnapshot(snap)
        }
    }

    private fun stopTelemetryLoop() {
        telemTimer?.cancel()
        telemTimer = null
    }

    override fun onDestroy() {
        stopVideoLoop()
        stopTelemetryLoop()
        videoStreamer?.stop()
        telemetryStreamer?.stop()
        detachPreview()
        TelemetryProvider.stop()
        super.onDestroy()
    }
}