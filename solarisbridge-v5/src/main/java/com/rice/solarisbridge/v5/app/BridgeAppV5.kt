package com.rice.solarisbridge.v5.app

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

/** Application entry point for the V5 app: installs the DJI helper (MSDK V5 init happens later). */
class BridgeAppV5 : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Helper.install(this)
    }
}