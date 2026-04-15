package com.rice.solarismatrice.drone.control

import android.util.Log
import com.rice.solarismatrice.drone.model.GimbalCmd
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalSpeedRotation
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

class GimbalController(
    private val tag: String = "GimbalController",
    private val gimbalIndex: Int = 0
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
        stopMotion()
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
        val rotation = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            yaw = yawDeg
            pitch = pitchDeg
            roll = rollDeg
            duration = durationSec
        }

        val key = KeyTools.createKey(GimbalKey.KeyRotateByAngle, gimbalIndex)

        KeyManager.getInstance().performAction(
            key,
            rotation,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) = Unit

                override fun onFailure(error: IDJIError) {
                    Log.e(tag, "Gimbal rotate failed: ${error.description()}")
                }
            }
        )
    }

    private fun stopMotion() {
        val speed = GimbalSpeedRotation().apply {
            yaw = 0.0
            pitch = 0.0
            roll = 0.0
        }

        val key = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, gimbalIndex)
        KeyManager.getInstance().performAction(
            key,
            speed,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) = Unit

                override fun onFailure(error: IDJIError) {
                    Log.w(tag, "Gimbal stop failed: ${error.description()}")
                }
            }
        )
    }
}