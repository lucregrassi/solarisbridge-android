package com.rice.solarisbridge.v4

import android.app.Application
import android.content.Context
import android.util.Log
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager

class BridgeAppV4 : Application() {

    companion object {
        private const val TAG = "BridgeAppV4"

        @Volatile
        private var sdkRegistered = false

        fun isSdkRegistered(): Boolean = sdkRegistered

        fun getProductInstance(): BaseProduct? {
            return DJISDKManager.getInstance().product
        }
    }

    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context
    }

    override fun onCreate() {
        super.onCreate()

        DJISDKManager.getInstance().registerApp(
            appContext,
            object : DJISDKManager.SDKManagerCallback {
                override fun onRegister(error: DJIError?) {
                    if (error == DJISDKError.REGISTRATION_SUCCESS) {
                        sdkRegistered = true
                        Log.i(TAG, "DJI SDK V4 registration success")
                        DJISDKManager.getInstance().startConnectionToProduct()
                    } else {
                        sdkRegistered = false
                        Log.e(TAG, "DJI SDK V4 registration failed: ${error?.description}")
                    }
                }

                override fun onProductDisconnect() {
                    Log.w(TAG, "Product disconnected")
                }

                override fun onProductConnect(product: BaseProduct?) {
                    Log.i(TAG, "Product connected: ${product?.model}")
                }

                override fun onProductChanged(product: BaseProduct?) {
                    Log.i(TAG, "Product changed: ${product?.model}")
                }

                override fun onComponentChange(
                    key: BaseProduct.ComponentKey?,
                    oldComponent: BaseComponent?,
                    newComponent: BaseComponent?
                ) = Unit

                override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) = Unit

                override fun onDatabaseDownloadProgress(current: Long, total: Long) = Unit
            }
        )
    }
}