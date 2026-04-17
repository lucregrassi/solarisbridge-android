package com.rice.solarisbridge.common.commands.parser

import com.rice.solarisbridge.common.commands.model.DroneCmd
import com.rice.solarisbridge.common.commands.model.GimbalCmd
import org.json.JSONObject

object CommandParsers {

    fun parseDrone(json: String): DroneCmd? = try {
        val o = JSONObject(json)
        DroneCmd(
            vx = o.getDouble("vx").toFloat(),
            vy = o.getDouble("vy").toFloat(),
            yaw = o.getDouble("yaw").toFloat(),
            throttle = o.getDouble("throttle").toFloat()
        )
    } catch (_: Throwable) { null }

    fun parseGimbal(json: String): GimbalCmd? = try {
        val o = JSONObject(json)
        GimbalCmd(
            yaw = o.getDouble("yaw").toFloat(),
            pitch = o.getDouble("pitch").toFloat(),
            roll = o.getDouble("roll").toFloat()
        )
    } catch (_: Throwable) { null }
}