package com.rice.solarisbridge.v4.drone.telemetry

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.common.streaming.EncodedVideoStreamer
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager

/**
 * Streams the drone camera to the PC and, in parallel, renders the local preview
 * on the phone.
 *
 * How the raw H.264 is obtained (DJI Mobile SDK V4):
 *   We register a single [VideoFeeder.VideoDataListener]. Inside it we:
 *     1) forward the raw H.264 bytes to the PC via [EncodedVideoStreamer], and
 *     2) feed [DJICodecManager] for the on-phone preview.
 *
 *   IMPORTANT (multi-lens aircraft such as the Mavic 2 Enterprise Dual / M300):
 *   for these products the *primary* video feed does NOT deliver data to listeners
 *   added with addVideoDataListener(); the SDK requires the *transcoded* feed
 *   (provideTranscodedVideoFeed()) whenever isFetchKeyFrameNeeded() or
 *   isLensDistortionCalibrationNeeded() are true. We therefore pick the correct
 *   feed at start(), mirroring the official DJI VideoStreamDecoding sample.
 */
class VideoStreamController(
    private val context: Context,
    private val previewView: TextureView,
    private val tag: String = "VideoStreamControllerV4"
) {

    private var codecManager: DJICodecManager? = null
    private var videoDataListener: VideoFeeder.VideoDataListener? = null
    private var activeFeed: VideoFeeder.VideoFeed? = null
    private var encodedStreamer: EncodedVideoStreamer? = null

    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    var isRunning: Boolean = false
        private set

    fun rebuildFromPrefs() {
        val ip = AppPrefs.getPcIp(context) ?: run {
            Log.w(tag, "PC IP null")
            if (isRunning) stop()
            encodedStreamer = null
            return
        }

        val videoTxPort = AppPrefs.getVideoTxPort(context)

        if (isRunning) {
            stop()
        }

        // Small queue: on a healthy LAN it stays near-empty, but it caps the
        // worst-case latency to ~0.6-0.7s if the network briefly falls behind
        // (instead of letting up to ~2s of frames accumulate), while leaving
        // enough margin to absorb normal network jitter without dropping frames.
        encodedStreamer = EncodedVideoStreamer(host = ip, port = videoTxPort, maxQueuedFrames = 20)
        Log.i(tag, "EncodedVideoStreamer rebuilt: $ip:$videoTxPort")
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

    /**
     * Picks the feed that actually delivers raw H.264 to added listeners.
     * Multi-lens aircraft need the transcoded feed; most others use the primary.
     */
    private fun resolveVideoFeed(): VideoFeeder.VideoFeed? {
        val feeder = VideoFeeder.getInstance() ?: run {
            Log.w(tag, "VideoFeeder null")
            return null
        }

        val needTranscoded = try {
            feeder.isFetchKeyFrameNeeded || feeder.isLensDistortionCalibrationNeeded
        } catch (t: Throwable) {
            Log.w(tag, "feed capability check failed, defaulting to primary", t)
            false
        }

        return if (needTranscoded) {
            Log.i(tag, "using TRANSCODED video feed (fetchKeyFrame/lensCalibration needed)")
            feeder.provideTranscodedVideoFeed()
        } else {
            Log.i(tag, "using PRIMARY video feed")
            feeder.primaryVideoFeed
        }
    }

    fun start(isPreviewAttached: Boolean) {
        if (isRunning) return

        val streamer = encodedStreamer ?: run {
            Log.w(tag, "EncodedStreamer null - set the PC IP in Settings first")
            return
        }

        val feed = resolveVideoFeed() ?: run {
            Log.w(tag, "No usable video feed")
            return
        }

        streamer.start()

        if (codecManager == null) {
            Log.w(tag, "CodecManager null - streaming to PC only, no on-phone preview")
        }

        var forwardedCount = 0L

        val listener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            if (size <= 0) return@VideoDataListener

            // 1) On-phone preview.
            codecManager?.let { cm ->
                try {
                    cm.sendDataToDecoder(videoBuffer, size)
                } catch (t: Throwable) {
                    Log.w(tag, "preview decode failed", t)
                }
            }

            // 2) Forward the RAW H.264 to the PC.
            try {
                streamer.sendFrame(videoBuffer, 0, size)
                forwardedCount++
                if (forwardedCount == 1L || forwardedCount % 60L == 0L) {
                    Log.i(tag, "listener firing: forwarded $forwardedCount chunks to PC (last=$size B)")
                }
            } catch (t: Throwable) {
                Log.w(tag, "PC stream forward failed", t)
            }
        }

        videoDataListener = listener
        activeFeed = feed
        feed.addVideoDataListener(listener)

        isRunning = true
        Log.i(tag, "Video stream STARTED (preview=${codecManager != null})")
    }

    fun stop() {
        if (!isRunning) return

        try {
            videoDataListener?.let { listener ->
                activeFeed?.removeVideoDataListener(listener)
            }
        } catch (t: Throwable) {
            Log.w(tag, "removeVideoDataListener failed", t)
        }

        videoDataListener = null
        activeFeed = null
        encodedStreamer?.stop()
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
            // Multi-lens / OcuSync aircraft may need an explicit key-frame request
            // so the decoder can start without waiting for the next natural I-frame.
            try {
                codecManager?.resetKeyFrame()
            } catch (_: Throwable) {
            }
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
