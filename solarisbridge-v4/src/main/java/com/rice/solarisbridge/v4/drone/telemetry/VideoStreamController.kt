package com.rice.solarisbridge.v4.drone.telemetry

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.common.streaming.JpegFrameStreamer
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager

class VideoStreamController(
    private val context: Context,
    private val previewView: TextureView,
    private val tag: String = "VideoStreamControllerV4"
) {

    private var codecManager: DJICodecManager? = null
    private var videoDataListener: VideoFeeder.VideoDataListener? = null
    private var jpegFrameStreamer: JpegFrameStreamer? = null

    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    var isRunning: Boolean = false
        private set

    fun rebuildFromPrefs() {
        val ip = AppPrefs.getPcIp(context) ?: run {
            Log.w(tag, "PC IP null")
            if (isRunning) stop()
            jpegFrameStreamer = null
            return
        }

        val videoTxPort = AppPrefs.getVideoTxPort(context)

        if (isRunning) {
            stop()
        }

        jpegFrameStreamer = JpegFrameStreamer(
            textureView = previewView,
            ip = ip,
            port = videoTxPort,
            fps = 8,
            jpegQuality = 70
        )

        Log.i(tag, "JpegFrameStreamer rebuilt: $ip:$videoTxPort")
    }

    fun onSurfaceAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surfaceTexture = st
        surfaceWidth = w
        surfaceHeight = h
        recreateCodecManager()
    }

    fun onSurfaceSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        surfaceTexture = st
        surfaceWidth = w
        surfaceHeight = h
        recreateCodecManager()
    }

    fun onSurfaceDestroyed() {
        stop()
        destroyCodecManager()
        surfaceTexture = null
        surfaceWidth = 0
        surfaceHeight = 0
    }

    fun start(isPreviewAttached: Boolean) {
        if (isRunning) return

        if (!isPreviewAttached) {
            Log.w(tag, "Preview not attached yet")
            return
        }

        if (codecManager == null) {
            Log.w(tag, "CodecManager null")
            return
        }

        val feed = VideoFeeder.getInstance()?.primaryVideoFeed ?: run {
            Log.w(tag, "Primary video feed null")
            return
        }

        val listener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            if (size <= 0) return@VideoDataListener

            try {
                codecManager?.sendDataToDecoder(videoBuffer, size)
            } catch (t: Throwable) {
                Log.w(tag, "preview decode failed", t)
            }
        }

        videoDataListener = listener
        feed.addVideoDataListener(listener)

        jpegFrameStreamer?.start()

        isRunning = true
        Log.i(tag, "Video stream STARTED")
    }

    fun stop() {
        if (!isRunning) return

        try {
            val feed = VideoFeeder.getInstance()?.primaryVideoFeed
            videoDataListener?.let { listener ->
                feed?.removeVideoDataListener(listener)
            }
        } catch (t: Throwable) {
            Log.w(tag, "removeVideoDataListener failed", t)
        }

        videoDataListener = null
        jpegFrameStreamer?.stop()
        isRunning = false
        Log.i(tag, "Video stream STOPPED")
    }

    fun toggle(isPreviewAttached: Boolean) {
        if (isRunning) stop() else start(isPreviewAttached)
    }

    private fun recreateCodecManager() {
        val st = surfaceTexture ?: run {
            Log.w(tag, "recreateCodecManager: surfaceTexture null")
            return
        }

        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            Log.w(tag, "recreateCodecManager: invalid size ${surfaceWidth}x$surfaceHeight")
            return
        }

        destroyCodecManager()

        try {
            codecManager = DJICodecManager(context, st, surfaceWidth, surfaceHeight)
            Log.i(tag, "CodecManager created: ${surfaceWidth}x$surfaceHeight")
        } catch (t: Throwable) {
            codecManager = null
            Log.e(tag, "Failed to create DJICodecManager", t)
        }
    }

    private fun destroyCodecManager() {
        try {
            codecManager?.cleanSurface()
        } catch (_: Throwable) {
        }

        try {
            codecManager?.destroyCodec()
        } catch (_: Throwable) {
        }

        codecManager = null
    }
}