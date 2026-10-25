package com.safeguard.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val requestCode = 417

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        root.addView(TextView(this).apply {
            text = "SafeGuard"
            textSize = 32f
            setTextColor(Color.rgb(22, 42, 72))
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Adult-site DNS protection"
            textSize = 17f
            setPadding(0, 8, 0, 24)
            setTextColor(Color.DKGRAY)
        })
        status = TextView(this).apply {
            text = "Protection is not active. Tap below and approve the Android VPN request."
            textSize = 16f
            setTextColor(Color.rgb(35, 65, 45))
            setPadding(0, 8, 0, 24)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Enable protection"
            textSize = 18f
            setOnClickListener {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent != null) startActivityForResult(intent, requestCode)
                else onActivityResult(requestCode, RESULT_OK, null)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "Once approved, SafeGuard sends ordinary DNS lookups through a family-filtering DNS service. No domain lists or typing are required. This is not a perfect filter: apps using encrypted DNS or built-in content may bypass DNS filtering."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 24, 0, 0)
        })
        setContentView(root)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            startService(Intent(this, SafeGuardVpnService::class.java))
            status.text = "Starting protection… Android will show a VPN key icon when the service is active."
        } else if (requestCode == this.requestCode) {
            status.text = "Protection was not enabled. Approve the VPN request to continue."
        }
    }
}