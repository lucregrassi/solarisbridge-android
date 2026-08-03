package com.rice.solarisbridge.v4.drone.control

import android.util.Log
import com.rice.solarisbridge.common.commands.model.GimbalCmd
import com.rice.solarisbridge.v4.app.BridgeBootstrapV4
import dji.common.error.DJIError
import dji.common.gimbal.GimbalMode
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.util.CommonCallbacks

/**
 * Applies gimbal commands from the PC (V4 gimbal API).
 *
 * The gimbal is kept in YAW_FOLLOW mode (see [applyFollowYawMode]): its yaw automatically tracks the
 * aircraft heading, so the camera stays aligned with the drone as it rotates and the PC does not
 * need to command yaw. Only pitch and roll are applied; yaw is left untouched (Rotation.NO_ROTATION).
 */
class GimbalController(
    private val tag: String = "GimbalControllerV4"
) {

    /** Puts the gimbal in YAW_FOLLOW mode so its yaw automatically follows the aircraft heading. */
    fun applyFollowYawMode() {
        val gimbal = BridgeBootstrapV4.getProductInstance()?.gimbal ?: run {
            Log.w(tag, "Gimbal null (setMode)")
            return
        }
        gimbal.setMode(GimbalMode.YAW_FOLLOW, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) {
                    Log.w(tag, "setMode(YAW_FOLLOW) failed: ${error.description}")
                } else {
                    Log.i(tag, "Gimbal mode = YAW_FOLLOW")
                }
            }
        })
    }

    fun applyCmd(cmd: GimbalCmd) {
        // yaw is intentionally ignored: it follows the aircraft (YAW_FOLLOW). Only pitch/roll move.
        sendAngleCommand(
            pitchDeg = cmd.pitch.toDouble(),
            rollDeg = cmd.roll.toDouble(),
            durationSec = 0.08
        )
    }

    fun moveToNeutral() {
        sendAngleCommand(
            pitchDeg = 0.0,
            rollDeg = 0.0,
            durationSec = 0.8
        )
    }

    private fun sendAngleCommand(
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
            .yaw(Rotation.NO_ROTATION)      // leave yaw to YAW_FOLLOW mode
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
