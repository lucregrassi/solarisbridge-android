package com.rice.solarisbridge.v4.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.rice.solarisbridge.common.network.NetUtils
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v4.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvDeviceIp: TextView
    private lateinit var etPcIp: EditText
    private lateinit var etTelemetryTxPort: EditText
    private lateinit var etVideoTxPort: EditText
    private lateinit var etFlightCmdRxPort: EditText
    private lateinit var etGimbalCmdRxPort: EditText
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindViews()
        loadCurrentValues()
        bindListeners()
    }

    private fun bindViews() {
        tvDeviceIp = findViewById(R.id.tvDeviceIp)
        etPcIp = findViewById(R.id.etPcIp)
        etTelemetryTxPort = findViewById(R.id.etTelemetryTxPort)
        etVideoTxPort = findViewById(R.id.etVideoTxPort)
        etFlightCmdRxPort = findViewById(R.id.etFlightCmdRxPort)
        etGimbalCmdRxPort = findViewById(R.id.etGimbalCmdRxPort)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)
    }

    private fun loadCurrentValues() {
        val deviceIp = NetUtils.getFirstLocalIpv4()

        tvDeviceIp.text = if (deviceIp != null) {
            getString(R.string.label_device_ip_format, deviceIp)
        } else {
            getString(R.string.label_device_ip_format, getString(R.string.label_not_available))
        }

        AppPrefs.getPcIp(this)?.let { etPcIp.setText(it) }

        etTelemetryTxPort.setText(AppPrefs.getTelemetryTxPort(this).toString())
        etVideoTxPort.setText(AppPrefs.getVideoTxPort(this).toString())
        etFlightCmdRxPort.setText(AppPrefs.getFlightCmdRxPort(this).toString())
        etGimbalCmdRxPort.setText(AppPrefs.getGimbalCmdRxPort(this).toString())
    }

    private fun bindListeners() {
        btnSave.setOnClickListener {
            validateSaveAndGo()
        }
    }

    private fun validateSaveAndGo() {
        val pcIp = etPcIp.text.toString().trim()
        val telemetryTxPort = etTelemetryTxPort.text.toString().toIntOrNull() ?: -1
        val videoTxPort = etVideoTxPort.text.toString().toIntOrNull() ?: -1
        val flightCmdRxPort = etFlightCmdRxPort.text.toString().toIntOrNull() ?: -1
        val gimbalCmdRxPort = etGimbalCmdRxPort.text.toString().toIntOrNull() ?: -1

        if (!NetUtils.isValidIpv4(pcIp)) {
            tvStatus.text = getString(R.string.error_invalid_pc_ip)
            return
        }

        if (telemetryTxPort !in VALID_PORT_RANGE) {
            tvStatus.text = getString(R.string.error_invalid_telemetry_port)
            return
        }

        if (videoTxPort !in VALID_PORT_RANGE) {
            tvStatus.text = getString(R.string.error_invalid_video_port)
            return
        }

        if (flightCmdRxPort !in VALID_PORT_RANGE) {
            tvStatus.text = getString(R.string.error_invalid_flight_command_port)
            return
        }

        if (gimbalCmdRxPort !in VALID_PORT_RANGE) {
            tvStatus.text = getString(R.string.error_invalid_gimbal_command_port)
            return
        }

        val ports = listOf(
            telemetryTxPort,
            videoTxPort,
            flightCmdRxPort,
            gimbalCmdRxPort
        )

        if (ports.toSet().size != ports.size) {
            tvStatus.text = getString(R.string.error_ports_must_be_different)
            return
        }

        AppPrefs.saveNetworkConfig(
            this@SettingsActivity,
            pcIp,
            telemetryTxPort,
            videoTxPort,
            flightCmdRxPort,
            gimbalCmdRxPort
        )

        tvStatus.text = getString(R.string.status_configuration_saved)

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
            tvStatus.text = getString(R.string.status_configuration_required)
            return
        }

        super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.app_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_main -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                true
            }

            R.id.menu_settings -> true

            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private val VALID_PORT_RANGE = 1024..65535
    }
}