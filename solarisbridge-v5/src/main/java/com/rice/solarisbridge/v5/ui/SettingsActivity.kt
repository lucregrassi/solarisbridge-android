package com.rice.solarisbridge.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.rice.solarisbridge.common.network.NetUtils
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v5.R

/**
 * Network configuration screen (V5): PC IP and RX/TX ports. Validates IPv4 format, port range and
 * port uniqueness before persisting through AppPrefs.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tvDeviceIp: TextView
    private lateinit var etPcIp: EditText
    private lateinit var etTelemetryTxPort: EditText
    private lateinit var etVideoTxPort: EditText
    private lateinit var etFlightCmdRxPort: EditText
    private lateinit var etGimbalCmdRxPort: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvDeviceIp = findViewById(R.id.tvDeviceIp)
        etPcIp = findViewById(R.id.etPcIp)
        etTelemetryTxPort = findViewById(R.id.etTelemetryTxPort)
        etVideoTxPort = findViewById(R.id.etVideoTxPort)
        etFlightCmdRxPort = findViewById(R.id.etFlightCmdRxPort)
        etGimbalCmdRxPort = findViewById(R.id.etGimbalCmdRxPort)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)

        val deviceIp = NetUtils.getFirstLocalIpv4() ?: getString(R.string.not_available)
        tvDeviceIp.text = getString(R.string.device_ip_format, deviceIp)

        AppPrefs.getPcIp(this)?.let { etPcIp.setText(it) }
        etTelemetryTxPort.setText(AppPrefs.getTelemetryTxPort(this).toString())
        etVideoTxPort.setText(AppPrefs.getVideoTxPort(this).toString())
        etFlightCmdRxPort.setText(AppPrefs.getFlightCmdRxPort(this).toString())
        etGimbalCmdRxPort.setText(AppPrefs.getGimbalCmdRxPort(this).toString())

        btnSave.setOnClickListener {
            validateSaveAndGo()
        }
    }

    private fun validateSaveAndGo() {
        val ip = etPcIp.text.toString().trim()
        val telemetryTxPort = etTelemetryTxPort.text.toString().toIntOrNull() ?: -1
        val videoTxPort = etVideoTxPort.text.toString().toIntOrNull() ?: -1
        val flightCmdRxPort = etFlightCmdRxPort.text.toString().toIntOrNull() ?: -1
        val gimbalCmdRxPort = etGimbalCmdRxPort.text.toString().toIntOrNull() ?: -1

        if (!NetUtils.isValidIpv4(ip)) {
            tvStatus.text = getString(R.string.error_invalid_pc_ip)
            return
        }
        if (telemetryTxPort !in 1024..65535) {
            tvStatus.text = getString(R.string.error_invalid_telemetry_port)
            return
        }
        if (videoTxPort !in 1024..65535) {
            tvStatus.text = getString(R.string.error_invalid_video_port)
            return
        }
        if (flightCmdRxPort !in 1024..65535) {
            tvStatus.text = getString(R.string.error_invalid_flight_cmd_port)
            return
        }
        if (gimbalCmdRxPort !in 1024..65535) {
            tvStatus.text = getString(R.string.error_invalid_gimbal_cmd_port)
            return
        }

        val ports = listOf(telemetryTxPort, videoTxPort, flightCmdRxPort, gimbalCmdRxPort)
        if (ports.toSet().size != ports.size) {
            tvStatus.text = getString(R.string.error_ports_must_be_different)
            return
        }

        AppPrefs.saveNetworkConfig(
            this@SettingsActivity,
            ip,
            telemetryTxPort,
            videoTxPort,
            flightCmdRxPort,
            gimbalCmdRxPort
        )

        tvStatus.text = getString(R.string.configuration_saved)

        val intent = Intent(this@SettingsActivity, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val pcIp = AppPrefs.getPcIp(this)
        if (pcIp.isNullOrBlank()) {
            tvStatus.text = getString(R.string.configuration_required_before_proceeding)
            return
        }
        super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}