package com.rice.solarisbridge.common.commands.parser

import com.rice.solarisbridge.common.commands.model.DroneCmd
import com.rice.solarisbridge.common.commands.model.GimbalCmd
import com.rice.solarisbridge.common.commands.model.WaypointGotoCmd
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

    /**
     * Parses a goto command (lat/lon/alt/speed + optional heading).
     * Returns null if any required field is missing or the JSON is malformed.
     * "heading" is optional: absent -> null (AUTO heading mode on the mission side).
     *
     * Example: {"lat":44.4012,"lon":8.9560,"alt":20.0,"speed":3.0,"heading":90.0}
     */
    fun parseGoto(json: String): WaypointGotoCmd? = try {
        val o = JSONObject(json)
        WaypointGotoCmd(
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            alt = o.getDouble("alt").toFloat(),
            speed = o.getDouble("speed").toFloat(),
            heading = if (o.has("heading") && !o.isNull("heading")) {
                o.getDouble("heading").toFloat()
            } else {
                null
            }
        )
    } catch (_: Throwable) { null }
}