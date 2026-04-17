package com.rice.solarisbridge.v5.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v5.ui.MainActivity
import com.rice.solarisbridge.v5.ui.SettingsActivity

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