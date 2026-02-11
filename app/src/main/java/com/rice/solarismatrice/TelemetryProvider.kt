package com.rice.solarismatrice

import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.IMUState
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object TelemetryProvider {

    private const val TAG = "TelemetryProvider"

    data class Snapshot(
        val ts: Long = System.currentTimeMillis(),

        val lat: Double? = null,
        val lon: Double? = null,
        val alt: Double? = null,

        val velX: Double? = null,
        val velY: Double? = null,
        val velZ: Double? = null,

        val roll: Double? = null,
        val pitch: Double? = null,
        val yaw: Double? = null,

        val batteryPercent: Int? = null,

        val homeLat: Double? = null,
        val homeLon: Double? = null,

        // “compass”: in pratica heading (deg)
        val compassHeading: Double? = null,

        // IMU states (health/calibration etc.)
        val imuStates: List<IMUState>? = null
    )

    private val snapshot = AtomicReference(Snapshot())
    private val started = AtomicBoolean(false)

    private fun update(block: (Snapshot) -> Snapshot) {
        snapshot.updateAndGet { old -> block(old.copy(ts = System.currentTimeMillis())) }
    }

    fun getSnapshot(): Snapshot = snapshot.get()

    private val listenHolder = Any()

    fun start() {
        if (!started.compareAndSet(false, true)) {
            Log.w(TAG, "start() called but already started")
            return
        }

        val km = KeyManager.getInstance()
        if (km == null) {
            Log.w(TAG, "KeyManager is null (SDK not ready?)")
            started.set(false)
            return
        }

        // 1) Aircraft location (lat/lon)
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftLocation),
            listenHolder,
            CommonCallbacks.KeyListener<LocationCoordinate2D> { _, newValue ->
                newValue ?: return@KeyListener
                update { s ->
                    s.copy(
                        lat = newValue.latitude,
                        lon = newValue.longitude
                    )
                }
            }
        )

        // 2) Altitude (Double nel tuo SDK)
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyAltitude),
            listenHolder,
            CommonCallbacks.KeyListener<Double> { _, newValue ->
                newValue ?: return@KeyListener
                update { s -> s.copy(alt = newValue) }
            }
        )

        // 3) Velocity (x/y/z) — convertiamo a Double esplicitamente (robusto)
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity),
            listenHolder,
            CommonCallbacks.KeyListener<Velocity3D> { _, newValue ->
                newValue ?: return@KeyListener
                update { s ->
                    s.copy(
                        velX = newValue.x.toDouble(),
                        velY = newValue.y.toDouble(),
                        velZ = newValue.z.toDouble()
                    )
                }
            }
        )

        // 4) Attitude (roll/pitch/yaw) — convertiamo a Double
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude),
            listenHolder,
            CommonCallbacks.KeyListener<Attitude> { _, newValue ->
                newValue ?: return@KeyListener
                val yaw = newValue.yaw.toDouble()
                update { s ->
                    s.copy(
                        roll = newValue.roll.toDouble(),
                        pitch = newValue.pitch.toDouble(),
                        yaw = yaw,
                        compassHeading = yaw
                    )
                }
            }
        )

        // 5) Battery remaining (%) — qui l’index potrebbe non essere LEFT_OR_MAIN su battery,
        // ma se nel tuo progetto compila e funziona lasciamo così.
        km.listen(
            KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent, ComponentIndexType.LEFT_OR_MAIN),
            listenHolder,
            CommonCallbacks.KeyListener<Int> { _, newValue ->
                // battery può essere null durante init/disconnect
                update { s -> s.copy(batteryPercent = newValue) }
            }
        )

        // 6) Home position
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyHomeLocation),
            listenHolder,
            CommonCallbacks.KeyListener<LocationCoordinate2D> { _, newValue ->
                newValue ?: return@KeyListener
                update { s ->
                    s.copy(
                        homeLat = newValue.latitude,
                        homeLon = newValue.longitude
                    )
                }
            }
        )

        // 7) IMU status (list)
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyIMUStatus),
            listenHolder
        ) { _, newValue ->
            // newValue può essere null durante init
            update { s -> s.copy(imuStates = newValue) }
        }

        Log.i(TAG, "TelemetryProvider started (listeners registered)")
    }

    fun stop() {
        try {
            KeyManager.getInstance()?.cancelListen(listenHolder)
        } catch (t: Throwable) {
            Log.w(TAG, "stop() error", t)
        } finally {
            started.set(false)
        }
    }
}