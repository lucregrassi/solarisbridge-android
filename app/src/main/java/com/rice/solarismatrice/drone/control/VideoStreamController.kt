package com.rice.solarismatrice.drone.control

import android.content.Context
import android.util.Log
import com.rice.solarismatrice.data.prefs.AppPrefs
import com.rice.solarismatrice.data.network.VideoStreamer
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

class VideoStreamController(
    private val context: Context,
    private val cameraIndex: ComponentIndexType,
    private val tag: String = "VideoStreamController"
) {
    private var videoStreamer: VideoStreamer? = null
    private var frameListener: ICameraStreamManager.CameraFrameListener? = null

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

        videoStreamer = VideoStreamer(ip, videoTxPort)
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

        videoStreamer?.start()

        val listener = ICameraStreamManager.CameraFrameListener { data, offset, length, width, height, _ ->
            if (!isRunning) return@CameraFrameListener
            if (length <= 0) return@CameraFrameListener

            val frame = ByteArray(length)
            System.arraycopy(data, offset, frame, 0, length)
            videoStreamer?.sendRawYuv(frame, width, height)
        }

        frameListener = listener
        mgr.addFrameListener(cameraIndex, ICameraStreamManager.FrameFormat.NV21, listener)

        isRunning = true
        Log.i(tag, "Video stream STARTED")
    }

    fun stop() {
        if (!isRunning) return

        val mgr = streamManager
        frameListener?.let { listener ->
            try {
                mgr?.removeFrameListener(listener)
            } catch (t: Throwable) {
                Log.w(tag, "removeFrameListener error", t)
            }
        }

        frameListener = null
        videoStreamer?.stop()
        isRunning = false
        Log.i(tag, "Video stream STOPPED")
    }

    fun toggle(isPreviewAttached: Boolean) {
        if (isRunning) stop() else start(isPreviewAttached)
    }
}