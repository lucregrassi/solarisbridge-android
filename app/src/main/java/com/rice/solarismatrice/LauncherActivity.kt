package com.rice.solarismatrice

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pcIp = AppPrefs.getPcIp(this)
        val next = if (pcIp.isNullOrBlank()) {
            Intent(this, SettingsActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        next.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(next)
        finish()
    }
}