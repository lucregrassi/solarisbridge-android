package com.rice.solarismatrice

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvControllerIp: TextView
    private lateinit var etPcIp: EditText
    private lateinit var etVideoPort: EditText
    private lateinit var etTelemPort: EditText
    private lateinit var etControllerPort: EditText // <-- nuovo campo
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvControllerIp = findViewById(R.id.tvControllerIp)
        etPcIp = findViewById(R.id.etPcIp)
        etVideoPort = findViewById(R.id.etVideoPort)
        etTelemPort = findViewById(R.id.etTelemPort)
        etControllerPort = findViewById(R.id.etControllerPort)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)

        // IP del controller (best-effort)
        tvControllerIp.text = "Controller IP: ${NetUtils.getLocalIpv4() ?: "non disponibile"}"

        // Precarica valori salvati (se esistono)
        AppPrefs.getPcIp(this)?.let { etPcIp.setText(it) }
        etVideoPort.setText(AppPrefs.getVideoPort(this).toString())
        etTelemPort.setText(AppPrefs.getTelemPort(this).toString())
        etControllerPort.setText(AppPrefs.getControllerPort(this).toString()) // <- precarica

        btnSave.setOnClickListener {
            validateSaveAndGo()
        }
    }

    private fun validateSaveAndGo() {
        val ip = etPcIp.text.toString().trim()
        val videoPort = etVideoPort.text.toString().toIntOrNull() ?: -1
        val telemPort = etTelemPort.text.toString().toIntOrNull() ?: -1
        val controllerPort = etControllerPort.text.toString().toIntOrNull() ?: -1

        // Validazioni immediate
        if (!NetUtils.isValidIpv4(ip)) {
            tvStatus.text = "IP del PC non valido"
            return
        }
        if (videoPort !in 1..65535) {
            tvStatus.text = "Porta VIDEO non valida"
            return
        }
        if (telemPort !in 1..65535) {
            tvStatus.text = "Porta TELEMETRIA non valida"
            return
        }
        if (controllerPort !in 1..65535) {
            tvStatus.text = "Porta CONTROLLER non valida"
            return
        }

        // Salva subito (nessun test di rete)
        AppPrefs.save(this@SettingsActivity, ip, videoPort, telemPort, controllerPort)
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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.app_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
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