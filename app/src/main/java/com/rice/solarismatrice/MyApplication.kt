package com.rice.solarismatrice

import android.app.Application
import android.content.Context
import android.util.Log
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback

class MyApplication : Application() {

    private val TAG = this::class.simpleName

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        SDKManager.getInstance().init(this,object:SDKManagerCallback{
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                Log.i(TAG, "onInitProcess: ")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    SDKManager.getInstance().registerApp()
                }
            }
            override fun onRegisterSuccess() {
                Log.i(TAG, "onRegisterSuccess: ")
            }
            override fun onRegisterFailure(error: IDJIError?) {
                Log.i(TAG, "onRegisterFailure: ")
            }
            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.i(TAG, "onDatabaseDownloadProgress: ${current/total}")
            }
            override fun onProductConnect(productId: Int) {
                Log.i(TAG, "onProductConnect: $productId")
                DroneState.setConnected(true)
            }
            override fun onProductDisconnect(productId: Int) {
                Log.i(TAG, "onProductDisconnect: $productId")
                DroneState.setConnected(false)
            }
            override fun onProductChanged(productId: Int) {
                Log.i(TAG, "onProductChanged: $productId")
                // nel dubbio, consideriamolo connesso
                DroneState.setConnected(true)
            }
        })
    }
}