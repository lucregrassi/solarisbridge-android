package com.rice.solarisbridge.v4.drone.control

import android.util.Log
import com.rice.solarisbridge.common.commands.model.GimbalCmd
import com.rice.solarisbridge.v4.app.BridgeBootstrapV4
import dji.common.error.DJIError
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.util.CommonCallbacks

/**
 * Applies gimbal commands from the PC as absolute-angle rotations (V4 gimbal API).
 * applyCmd uses a short duration for responsive tracking; moveToNeutral recenters slowly.
 */
class GimbalController(
    private val tag: String = "GimbalControllerV4"
) {

    fun applyCmd(cmd: GimbalCmd) {
        sendAngleCommand(
            yawDeg = cmd.yaw.toDouble(),
            pitchDeg = cmd.pitch.toDouble(),
            rollDeg = cmd.roll.toDouble(),
            durationSec = 0.08
        )
    }

    fun moveToNeutral() {
        sendAngleCommand(
            yawDeg = 0.0,
            pitchDeg = 0.0,
            rollDeg = 0.0,
            durationSec = 0.8
        )
    }

    private fun sendAngleCommand(
        yawDeg: Double,
        pitchDeg: Double,
        rollDeg: Double,
        durationSec: Double
    ) {
        val gimbal = BridgeBootstrapV4.getProductInstance()?.gimbal ?: run {
            Log.w(tag, "Gimbal null")
            return
        }

        val rotation = Rotation.Builder()
            .mode(RotationMode.ABSOLUTE_ANGLE)
            .yaw(yawDeg.toFloat())
            .pitch(pitchDeg.toFloat())
            .roll(rollDeg.toFloat())
            .time(durationSec)
            .build()

        gimbal.rotate(rotation, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) {
                    Log.e(tag, "Gimbal rotate failed: ${error.description}")
                }
            }
        })
    }
}