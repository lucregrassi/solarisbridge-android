package com.rice.solarisbridge.v4.drone.control

import android.util.Log
import com.rice.solarisbridge.v4.app.BridgeBootstrapV4
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightAssistant
import dji.sdk.products.Aircraft

class ObstacleAvoidanceController(
    private val tag: String = "ObstacleAvoidanceV4",
    private val onStateChanged: (Boolean?) -> Unit = {},
    private val onStatusLine: (String) -> Unit = {}
) {

    @Volatile
    var isEnabled: Boolean? = null
        private set

    fun refresh() {
        val assistant = flightAssistant()

        if (assistant == null) {
            Log.w(tag, "FlightAssistant null")
            isEnabled = null
            onStateChanged(null)
            onStatusLine("Obstacle avoidance not available")
            return
        }

        try {
            assistant.getCollisionAvoidanceEnabled(
                object : CommonCallbacks.CompletionCallbackWith<Boolean> {
                    override fun onSuccess(enabled: Boolean?) {
                        isEnabled = enabled
                        Log.i(tag, "Collision avoidance state: $enabled")
                        onStateChanged(enabled)
                    }

                    override fun onFailure(error: DJIError?) {
                        Log.w(tag, "getCollisionAvoidanceEnabled failed: ${error?.description}")
                        isEnabled = null
                        onStateChanged(null)
                        onStatusLine("OA read FAIL: ${error?.description}")
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(tag, "refresh exception", t)
            isEnabled = null
            onStateChanged(null)
            onStatusLine("OA read exception")
        }
    }

    fun toggle() {
        val current = isEnabled

        if (current == null) {
            refresh()
            return
        }

        setEnabled(!current)
    }

    fun setEnabled(enabled: Boolean) {
        val assistant = flightAssistant()

        if (assistant == null) {
            Log.w(tag, "FlightAssistant null")
            isEnabled = null
            onStateChanged(null)
            onStatusLine("Obstacle avoidance not available")
            return
        }

        try {
            assistant.setCollisionAvoidanceEnabled(
                enabled,
                object : CommonCallbacks.CompletionCallback<DJIError> {
                    override fun onResult(error: DJIError?) {
                        if (error != null) {
                            Log.w(tag, "setCollisionAvoidanceEnabled($enabled) failed: ${error.description}")
                            onStatusLine("OA set FAIL: ${error.description}")
                            refresh()
                            return
                        }

                        isEnabled = enabled
                        Log.i(tag, "Collision avoidance set to $enabled")
                        onStateChanged(enabled)
                        onStatusLine("Obstacle avoidance ${if (enabled) "ON" else "OFF"}")
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(tag, "setEnabled exception", t)
            onStatusLine("OA set exception")
            refresh()
        }
    }

    private fun flightAssistant(): FlightAssistant? {
        val aircraft = BridgeBootstrapV4.getProductInstance() as? Aircraft ?: return null
        if (!aircraft.isConnected) return null

        val flightController = aircraft.flightController ?: return null
        return flightController.flightAssistant
    }
}