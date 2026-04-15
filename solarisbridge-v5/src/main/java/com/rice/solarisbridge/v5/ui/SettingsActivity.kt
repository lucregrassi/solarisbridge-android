package com.rice.solarisbridge.v5.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.rice.solarisbridge.v5.data.prefs.AppPrefs
import com.rice.solarisbridge.v5.data.network.NetUtils
import com.rice.solarisbridge.v5.R

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

        tvDeviceIp = findViewById(R.id.tvDeviceIp)
        etPcIp = findViewById(R.id.etPcIp)
        etTelemetryTxPort = findViewById(R.id.etTelemetryTxPort)
        etVideoTxPort = findViewById(R.id.etVideoTxPort)
        etFlightCmdRxPort = findViewById(R.id.etFlightCmdRxPort)
        etGimbalCmdRxPort = findViewById(R.id.etGimbalCmdRxPort)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)

        // IP del controller (best-effort)
        tvDeviceIp.text = "Device IP: ${NetUtils.getFirstLocalIpv4() ?: "non disponibile"}"

        // Precarica valori salvati (se esistono)
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

        // Validazioni immediate
        if (!NetUtils.isValidIpv4(ip)) {
            tvStatus.text = "IP del PC non valido"
            return
        }
        if (telemetryTxPort !in 1024..65535) {
            tvStatus.text = "Porta TELEMETRY non valida"
            return
        }
        if (videoTxPort !in 1024..65535) {
            tvStatus.text = "Porta VIDEO non valida"
            return
        }
        if (flightCmdRxPort !in 1024..65535) {
            tvStatus.text = "Porta FLIGHT CMD non valida"
            return
        }
        if (gimbalCmdRxPort !in 1024..65535) {
            tvStatus.text = "Porta GIMBAL CMD non valida"
            return
        }

        val ports = listOf(telemetryTxPort, videoTxPort, flightCmdRxPort, gimbalCmdRxPort)
        if (ports.toSet().size != ports.size) {
            tvStatus.text = "Le porte devono essere tutte diverse"
            return
        }

        // Salva subito (nessun test di rete)
        AppPrefs.saveNetworkConfig(
            this@SettingsActivity,
            ip,
            telemetryTxPort,
            videoTxPort,
            flightCmdRxPort,
            gimbalCmdRxPort
        )

        tvStatus.text = "Configurazione salvata."

        // Vai alla MainActivity
        val intent = Intent(this@SettingsActivity, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Impedisce di uscire se la configurazione non è valida.
     */
    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val pcIp = AppPrefs.getPcIp(this)
        if (pcIp.isNullOrBlank()) {
            tvStatus.text = "Configurazione obbligatoria prima di procedere"
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
            R.id.menu_settings -> {
                // Sei già in Settings -> niente
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}