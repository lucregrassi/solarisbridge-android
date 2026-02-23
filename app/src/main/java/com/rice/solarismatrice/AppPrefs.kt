package com.rice.solarismatrice

import android.content.Context
import androidx.core.content.edit

object AppPrefs {
    private const val PREFS = "solaris_prefs"
    private const val KEY_PC_IP = "pc_ip"
    private const val KEY_VIDEO_PORT = "video_port"
    private const val KEY_TELEM_PORT = "telem_port"
    private const val KEY_CONTROLLER_PORT = "controller_port"

    fun getPcIp(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PC_IP, null)

    fun getVideoPort(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VIDEO_PORT, 6000)

    fun getTelemPort(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TELEM_PORT, 6001)

    fun getControllerPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CONTROLLER_PORT, 7000)

    fun save(ctx: Context, ip: String, videoPort: Int, telemPort: Int, controllerPort: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_PC_IP, ip)
                    .putInt(KEY_VIDEO_PORT, videoPort)
                    .putInt(KEY_TELEM_PORT, telemPort)
                    .putInt(KEY_CONTROLLER_PORT, controllerPort)
            }
    }
}