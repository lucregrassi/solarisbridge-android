package com.rice.solarisbridge.v4

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvSdkStatus: TextView
    private lateinit var tvProductStatus: TextView
    private lateinit var tvModel: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSdkStatus = findViewById(R.id.tvSdkStatus)
        tvProductStatus = findViewById(R.id.tvProductStatus)
        tvModel = findViewById(R.id.tvModel)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun renderStatus() {
        val product = BridgeAppV4.getProductInstance()

        tvSdkStatus.text = if (BridgeAppV4.isSdkRegistered()) {
            "SDK status: REGISTERED"
        } else {
            "SDK status: NOT REGISTERED"
        }

        if (product != null && product.isConnected) {
            tvProductStatus.text = "Product status: CONNECTED"
            tvModel.text = "Model: ${product.model ?: "-"}"
        } else {
            tvProductStatus.text = "Product status: NOT CONNECTED"
            tvModel.text = "Model: -"
        }
    }
}