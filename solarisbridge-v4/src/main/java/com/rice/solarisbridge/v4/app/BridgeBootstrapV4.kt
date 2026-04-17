package com.rice.solarisbridge.v4.app

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper
import com.rice.solarisbridge.v4.app.BridgeAppV4

class BridgeBootstrapV4 : Application() {

    private var appDelegate: BridgeAppV4? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Helper.install(this)

        if (appDelegate == null) {
            appDelegate = BridgeAppV4()
            appDelegate?.setContext(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appDelegate?.onCreate()
    }
}