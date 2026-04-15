package com.rice.solarisbridge.v5.app

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

class BridgeAppV5 : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Helper.install(this)
    }
}