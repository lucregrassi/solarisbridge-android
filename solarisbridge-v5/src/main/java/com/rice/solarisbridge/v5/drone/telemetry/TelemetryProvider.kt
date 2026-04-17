package com.rice.solarisbridge.v5.drone.telemetry

import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIFlightControllerKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.rice.solarisbridge.common.telemetry.model.TelemetrySnapshot

object TelemetryProvider {

    private const val TAG = "TelemetryProvider"

    private val snapshot = AtomicReference(TelemetrySnapshot())
    private val listening = AtomicBoolean(false)
    private val listenHolder = Any()

    fun getSnapshot(): TelemetrySnapshot = snapshot.get()

    private fun update(block: (TelemetrySnapshot) -> TelemetrySnapshot) {
        snapshot.updateAndGet { old -> block(old.copy(ts = System.currentTimeMillis())) }
    }

    // tipizzato: GET iniziale + LISTEN
    private fun <T : Any> listenAndFetch(km: KeyManager, key: DJIKey<T>, onValue: (T) -> Unit) {
        try {
            km.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(t: T) {
                    onValue(t)
                }

                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "getValue failed for key=$key: ${error.description()}")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "getValue exception for $key", t)
        }

        // listen updates
        try {
            km.listen(key, listenHolder) { _, v ->
                if (v != null) onValue(v)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "listen exception for $key", t)
        }
    }

    /** Start telemetry collection (idempotent). */
    fun startIfNeeded() {
        if (!listening.compareAndSet(false, true)) {
            Log.i(TAG, "TelemetryProvider.startIfNeeded(): already listening")
            return
        }

        val km = KeyManager.getInstance()
        if (km == null) {
            Log.w(TAG, "KeyManager null, cannot start TelemetryProvider")
            listening.set(false)
            return
        }

        // Define keys with correct generic types
        val kLoc3d: DJIKey<LocationCoordinate3D> = KeyTools.createKey(DJIFlightControllerKey.KeyAircraftLocation3D)
        val kUltra: DJIKey<Int> = KeyTools.createKey(DJIFlightControllerKey.KeyUltrasonicHeight)
        val kVel: DJIKey<Velocity3D> = KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity)
        val kAtt: DJIKey<Attitude> = KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude)
        val kBatt: DJIKey<Int> = KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent, ComponentIndexType.LEFT_OR_MAIN)
        val kHome: DJIKey<LocationCoordinate2D> = KeyTools.createKey(FlightControllerKey.KeyHomeLocation)

        // Register GET+LISTEN for each
        listenAndFetch(km, kLoc3d) { v ->
            update { s -> s.copy(lat = v.latitude, lon = v.longitude) }
            Log.d(TAG, "LOC3D got lat=${v.latitude} lon=${v.longitude}")
        }

        listenAndFetch(km, kUltra) { v ->
            val meters = try { v.toDouble() / 10.0 } catch (_: Throwable) { v.toDouble() }
            update { s -> s.copy(ultrasonicHeight = meters) }
            Log.d(TAG, "ULTRA raw=$v -> ${meters}m")
        }

        listenAndFetch(km, kVel) { v ->
            update { s -> s.copy(velX = v.x.toDouble(), velY = v.y.toDouble(), velZ = v.z.toDouble()) }
        }

        listenAndFetch(km, kAtt) { v ->
            val yaw = v.yaw.toDouble()
            update { s -> s.copy(roll = v.roll.toDouble(), pitch = v.pitch.toDouble(), yaw = yaw, compassHeading = yaw) }
        }

        listenAndFetch(km, kBatt) { v ->
            update { s -> s.copy(batteryPercent = v) }
        }

        listenAndFetch(km, kHome) { v ->
            update { s -> s.copy(homeLat = v.latitude, homeLon = v.longitude) }
        }

        Log.i(TAG, "TelemetryProvider started (GET + LISTEN)")
    }

    /** Stop telemetry collection (idempotent). */
    fun stopIfNeeded() {
        if (!listening.compareAndSet(true, false)) {
            Log.i(TAG, "TelemetryProvider.stopIfNeeded(): not listening")
            return
        }
        try {
            KeyManager.getInstance()?.cancelListen(listenHolder)
        } catch (t: Throwable) {
            Log.w(TAG, "cancelListen error", t)
        }
        Log.i(TAG, "TelemetryProvider stopped")
    }
}