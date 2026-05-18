package com.rice.solarisbridge.v4.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.cySdkyc.clx.Helper
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager

class BridgeBootstrapV4 : Application() {

    companion object {
        private const val TAG = "BridgeBootstrapV4"

        @Volatile
        private var sdkRegistered = false

        fun isSdkRegistered(): Boolean = sdkRegistered

        fun getProductInstance(): BaseProduct? {
            return DJISDKManager.getInstance().product
        }

        fun isProductConnected(): Boolean {
            val product = DJISDKManager.getInstance().product
            return product != null && product.isConnected
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        Log.i(TAG, "attachBaseContext: before Helper.install")
        Helper.install(this)
        Log.i(TAG, "attachBaseContext: after Helper.install")
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "onCreate: before DJISDKManager.registerApp")

        DJISDKManager.getInstance().registerApp(
            this,
            object : DJISDKManager.SDKManagerCallback {

                override fun onRegister(error: DJIError?) {
                    Log.i(TAG, "onRegister called: error=$error description=${error?.description}")

                    if (error == DJISDKError.REGISTRATION_SUCCESS) {
                        sdkRegistered = true
                        Log.i(TAG, "DJI SDK V4 registration success")

                        DJISDKManager.getInstance().startConnectionToProduct()
                        Log.i(TAG, "startConnectionToProduct called")
                    } else {
                        sdkRegistered = false
                        Log.e(TAG, "DJI SDK V4 registration failed: ${error?.description}")
                    }
                }

                override fun onProductConnect(product: BaseProduct?) {
                    Log.i(
                        TAG,
                        "Product connected: product=$product model=${product?.model} connected=${product?.isConnected}"
                    )
                }

                override fun onProductDisconnect() {
                    Log.w(TAG, "Product disconnected")
                }

                override fun onProductChanged(product: BaseProduct?) {
                    Log.i(
                        TAG,
                        "Product changed: product=$product model=${product?.model} connected=${product?.isConnected}"
                    )
                }

                override fun onComponentChange(
                    key: BaseProduct.ComponentKey?,
                    oldComponent: BaseComponent?,
                    newComponent: BaseComponent?
                ) {
                    Log.i(
                        TAG,
                        "Component changed: key=$key old=$oldComponent new=$newComponent connected=${newComponent?.isConnected}"
                    )
                }

                override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                    Log.i(TAG, "DJI init process: event=$event totalProcess=$totalProcess")
                }

                override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                    Log.i(TAG, "DJI database download: $current / $total")
                }
            }
        )

        Log.i(TAG, "onCreate: after DJISDKManager.registerApp")
    }
}