package com.rice.solarisbridge.v5.data.prefs

import android.content.Context
import androidx.core.content.edit

object AppPrefs {
    private const val PREFS = "solaris_prefs"

    private const val KEY_PC_IP = "pc_ip"
    private const val KEY_TELEMETRY_TX_PORT = "telemetry_tx_port"
    private const val KEY_VIDEO_TX_PORT = "video_tx_port"
    private const val KEY_FLIGHT_CMD_RX_PORT = "flight_cmd_rx_port"
    private const val KEY_GIMBAL_CMD_RX_PORT = "gimbal_cmd_rx_port"

    fun getPcIp(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PC_IP, null)

    fun getTelemetryTxPort(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TELEMETRY_TX_PORT, 6000)

    fun getVideoTxPort(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VIDEO_TX_PORT, 6001)

    fun getFlightCmdRxPort(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FLIGHT_CMD_RX_PORT, 7000)

    fun getGimbalCmdRxPort(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_GIMBAL_CMD_RX_PORT, 7001)

    fun saveNetworkConfig(
        ctx: Context,
        ip: String,
        telemetryTxPort: Int,
        videoTxPort: Int,
        flightCmdRxPort: Int,
        gimbalCmdRxPort: Int
    ) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_PC_IP, ip)
                putInt(KEY_TELEMETRY_TX_PORT, telemetryTxPort)
                putInt(KEY_VIDEO_TX_PORT, videoTxPort)
                putInt(KEY_FLIGHT_CMD_RX_PORT, flightCmdRxPort)
                putInt(KEY_GIMBAL_CMD_RX_PORT, gimbalCmdRxPort)
            }
    }
}