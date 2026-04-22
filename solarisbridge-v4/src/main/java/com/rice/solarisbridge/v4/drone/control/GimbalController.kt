package com.rice.solarisbridge.v4.drone.control

import android.util.Log
import com.rice.solarisbridge.common.commands.model.GimbalCmd
import com.rice.solarisbridge.v4.app.BridgeAppV4
import dji.common.error.DJIError
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.util.CommonCallbacks

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
        val gimbal = BridgeAppV4.getProductInstance()?.gimbal ?: run {
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