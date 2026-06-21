package com.rice.solarisbridge.v5.drone.telemetry

import android.content.Context
import android.util.Log
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.common.streaming.EncodedVideoStreamer
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

/**
 * Streams the V5 camera to the PC. Receives the encoded camera stream via the camera stream
 * manager's ReceiveStreamListener and forwards each frame through [EncodedVideoStreamer] (TCP).
 */
class VideoStreamController(
    private val context: Context,
    private val cameraIndex: ComponentIndexType,
    private val tag: String = "VideoStreamController"
){

    private var encodedVideoStreamer: EncodedVideoStreamer? = null
    private var frameListener: ICameraStreamManager.ReceiveStreamListener? = null

    private val streamManager: ICameraStreamManager?
        get() = MediaDataCenter.getInstance().cameraStreamManager

    var isRunning: Boolean = false
        private set

    fun rebuildFromPrefs() {
        val ip = AppPrefs.getPcIp(context) ?: return
        val videoTxPort = AppPrefs.getVideoTxPort(context)

        if (isRunning) {
            stop()
        }

        encodedVideoStreamer = EncodedVideoStreamer(ip, videoTxPort)
    }

    fun start(isPreviewAttached: Boolean) {
        val mgr = streamManager ?: run {
            Log.w(tag, "CameraStreamManager null")
            return
        }

        if (!isPreviewAttached) {
            Log.w(tag, "Preview not attached yet")
            return
        }

        if (isRunning) return
        encodedVideoStreamer?.start()
        isRunning = true
        val listener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, _ ->
            if (!isRunning) return@ReceiveStreamListener

            if (length <= 0) return@ReceiveStreamListener

            encodedVideoStreamer?.sendFrame(data, offset, length)

        }

        frameListener = listener

        mgr.addReceiveStreamListener(cameraIndex, listener)

        Log.i(tag, "Encoded video stream STARTED")
    }

    fun stop() {
        if (!isRunning) return

        val mgr = streamManager
        frameListener?.let { listener ->
            try {
                mgr?.removeReceiveStreamListener(listener)
            } catch (t: Throwable) {
                Log.w(tag, "removeReceiveStreamListener error", t)
            }
        }

        frameListener = null
        encodedVideoStreamer?.stop()
        isRunning = false
        Log.i(tag, "Encoded video stream STOPPED")
    }

    fun toggle(isPreviewAttached: Boolean) {
        if (isRunning) stop() else start(isPreviewAttached)
    }
}