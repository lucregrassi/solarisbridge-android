package com.rice.solarismatrice

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvControllerIp: TextView
    private lateinit var etPcIp: EditText
    private lateinit var etVideoPort: EditText
    private lateinit var etTelemPort: EditText
    private lateinit var btnTest: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvControllerIp = findViewById(R.id.tvControllerIp)
        etPcIp = findViewById(R.id.etPcIp)
        etVideoPort = findViewById(R.id.etVideoPort)
        etTelemPort = findViewById(R.id.etTelemPort)
        btnTest = findViewById(R.id.btnTest)
        tvStatus = findViewById(R.id.tvStatus)

        // IP del controller (best-effort)
        tvControllerIp.text = "Controller IP: ${NetUtils.getLocalIpv4() ?: "non disponibile"}"

        // Precarica valori salvati (se esistono)
        AppPrefs.getPcIp(this)?.let { etPcIp.setText(it) }
        etVideoPort.setText(AppPrefs.getVideoPort(this).toString())
        etTelemPort.setText(AppPrefs.getTelemPort(this).toString())

        btnTest.setOnClickListener {
            testAndSave()
        }
    }

    private fun testAndSave() {
        val ip = etPcIp.text.toString().trim()
        val videoPort = etVideoPort.text.toString().toIntOrNull() ?: -1
        val telemPort = etTelemPort.text.toString().toIntOrNull() ?: -1

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

        btnTest.isEnabled = false
        tvStatus.text = "Test connessione TCP in corso..."

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                testTcpConnection(ip, videoPort)
            }

            if (ok) {
                tvStatus.text = "Connessione OK. Configurazione salvata."
                AppPrefs.save(this@SettingsActivity, ip, videoPort, telemPort)

                // Vai alla MainActivity
                val intent = Intent(this@SettingsActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } else {
                tvStatus.text =
                    "Connessione fallita.\n" +
                            "Controlla che il PC sia acceso,\n" +
                            "che il server TCP sia in ascolto\n" +
                            "e che firewall/rete permettano la connessione."
                btnTest.isEnabled = true
            }
        }
    }

    private fun testTcpConnection(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 1500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Impedisce di uscire se la configurazione non è valida.
     * Questo garantisce che l'app non possa arrivare alla MainActivity
     * senza IP/porte correttamente impostati.
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
}