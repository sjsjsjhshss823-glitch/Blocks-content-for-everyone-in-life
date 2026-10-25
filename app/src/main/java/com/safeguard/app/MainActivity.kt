package com.safeguard.app

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.role.RoleManager
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val requestCode = 417
    private val adminRequestCode = 418
    private val prefs by lazy { getSharedPreferences("safeguard_diagnostics", MODE_PRIVATE) }
    private val securityPrefs by lazy { getSharedPreferences("safeguard_security", MODE_PRIVATE) }
    private val navy = Color.rgb(18, 35, 67)
    private val teal = Color.rgb(0, 139, 133)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (securityPrefs.getString("pin_hash", null) != null) {
            showPinDialog(false)
        } else {
            showMainScreen()
        }
    }

    private fun showMainScreen() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(28), dp(22), dp(26))
            setBackgroundColor(Color.rgb(245, 248, 252))
        }
        root.addView(TextView(this).apply {
            text = "🛡️"
            textSize = 42f
            gravity = Gravity.CENTER
        }, match())
        root.addView(TextView(this).apply {
            text = "SafeGuard"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(navy)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        }, match())
        root.addView(TextView(this).apply {
            text = "הגנה בטוחה"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(92, 109, 132))
            setPadding(0, 0, 0, dp(20))
        }, match())
        status = TextView(this).apply {
            text = "בחרו את האישורים להפעלת ההגנה"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(navy)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(status, match())
        addAction(root, "הפעלת סינון", "אישור חיבור VPN") { requestVpn() }
        addAction(root, "אישור ניהול מכשיר", "ניהול הרשאות מנהל") { requestDeviceAdmin() }
        addAction(root, "אישור נתוני שימוש", "פתיחת הרשאת גישה לנתוני שימוש") {
            openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), "לא ניתן לפתוח את הגדרות נתוני השימוש")
        }
        addAction(root, "אישור ציור מעל אפליקציות", "הרשאת הצגה מעל יישומים אחרים") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), "לא ניתן לפתוח את הרשאת הציור")
            } else status.text = "ההרשאה אינה נדרשת בגרסת Android זו"
        }
        addAction(root, "הגדרת SafeGuard כדף הבית", "בחירת אפליקציית מסך הבית") { requestHomeRole() }
        addAction(root, "הגדרת קוד אישי", "קוד כניסה בן 4–8 ספרות") { showPinDialog(true) }
        addAction(root, "דיווח תקלות", "אבחון, הרשאות ושיתוף דוח") { showAndShareReport() }
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun addAction(root: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = "$title\n$subtitle"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(teal))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { action() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62))
        lp.topMargin = dp(9)
        root.addView(button, lp)
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, requestCode)
        else {
            startService(Intent(this, SafeGuardVpnService::class.java))
            status.text = "בקשת VPN כבר אושרה"
        }
    }

    private fun requestDeviceAdmin() {
        val component = ComponentName(this, SafeGuardAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "האישור מאפשר להפעיל יכולות ניהול בסיסיות של SafeGuard. הוא אינו מעניק שליטה בלתי מוגבלת במכשיר.")
        }
        startActivityForResult(intent, adminRequestCode)
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager.isRoleAvailable(RoleManager.ROLE_HOME) && !manager.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_HOME), 419)
            } else status.text = "SafeGuard כבר מוגדרת כדף הבית או שהאפשרות אינה זמינה"
        } else {
            openSettings(Intent(Settings.ACTION_HOME_SETTINGS), "לא ניתן לפתוח את בחירת דף הבית")
        }
    }

    private fun openSettings(intent: Intent, error: String) {
        try { startActivity(intent) } catch (e: Exception) {
            status.text = "$error: ${e.javaClass.simpleName}"
            saveError("פתיחת הגדרות", e)
        }
    }

    private fun showPinDialog(setup: Boolean) {
        val first = EditText(this).apply {
            hint = if (setup) "קוד חדש: 4–8 ספרות" else "הזינו את הקוד"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val second = if (setup) EditText(this).apply {
            hint = "אימות הקוד"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        } else null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(first, match())
            if (second != null) addView(second, match())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (setup) "הגדרת קוד אישי" else "SafeGuard נעולה")
            .setMessage(if (setup) "הקוד נשמר במכשיר ומשמש לפתיחת האפליקציה." else "הזינו את הקוד כדי להמשיך.")
            .setView(box)
            .setCancelable(!setup)
            .setPositiveButton(if (setup) "שמירת קוד" else "אישור", null)
            .apply { if (!setup) setNegativeButton("סגירה") { _, _ -> finish() } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = first.text.toString()
                if (setup) {
                    if (!pin.matches(Regex("\\d{4,8}"))) { first.error = "יש לבחור 4–8 ספרות"; return@setOnClickListener }
                    if (pin != second?.text.toString()) { second?.error = "הקודים אינם תואמים"; return@setOnClickListener }
                    securityPrefs.edit().putString("pin_hash", hash(pin)).apply()
                    dialog.dismiss()
                    status.text = "הקוד האישי נשמר"
                } else {
                    if (hash(pin) == securityPrefs.getString("pin_hash", null)) {
                        dialog.dismiss()
                        showMainScreen()
                    } else first.error = "קוד שגוי"
                }
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun showAndShareReport() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        fun time(key: String): String {
            val value = prefs.getLong(key, 0L)
            return if (value > 0) dateFormat.format(Date(value)) else "לא תועד"
        }
        val admin = (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(ComponentName(this, SafeGuardAdminReceiver::class.java))
        val usage = try {
            val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
                else @Suppress("DEPRECATION") appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val report = buildString {
            append("דוח אבחון SafeGuard\n")
            append("גרסת Android: ").append(Build.VERSION.RELEASE).append("\n")
            append("מכשיר: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
            append("מנהל מכשיר: ").append(if (admin) "מאושר" else "לא מאושר").append("\n")
            append("נתוני שימוש: ").append(if (usage) "מאושרים" else "לא מאושרים").append("\n")
            append("ציור מעל אפליקציות: ").append(if (overlay) "מאושר" else "לא מאושר").append("\n")
            append("תפקיד דף הבית: ").append(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)) "מאושר" else "לא נבחר/לא ניתן לאימות").append("\n")
            append("הפעלה אחרונה: ").append(time("last_start")).append("\n")
            append("פעילות אחרונה: ").append(time("last_heartbeat")).append("\n")
            append("עצירה אחרונה: ").append(time("last_stop")).append("\n")
            append("זמן שגיאה אחרונה: ").append(time("last_error_time")).append("\n")
            append("פרטי שגיאה: ").append(prefs.getString("last_error", "לא תועדה שגיאה") ?: "לא תועדה שגיאה").append("\n")
            append("הערה: Android אינו מאפשר לאפליקציה רגילה לחסום באופן מוחלט הסרה או לתעד כל סגירה בכוח.")
        }
        status.text = "דוח האבחון מוכן לשיתוף"
        AlertDialog.Builder(this).setTitle("דיווח תקלות").setMessage(report)
            .setPositiveButton("שיתוף הדוח") { _, _ ->
                try {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "דוח תקלה של SafeGuard")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    startActivity(Intent.createChooser(share, "שיתוף דוח התקלה"))
                } catch (e: Exception) { saveError("שיתוף דוח", e); status.text = "לא ניתן לשתף את הדוח" }
            }.setNegativeButton("סגירה", null).show()
    }

    private fun saveError(context: String, error: Exception) {
        prefs.edit().putLong("last_error_time", System.currentTimeMillis())
            .putString("last_error", "$context: ${error.javaClass.simpleName}: ${error.message ?: "ללא פרטים"}").apply()
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            this.requestCode -> if (resultCode == RESULT_OK) {
                startService(Intent(this, SafeGuardVpnService::class.java))
                status.text = "ההגנה מתחילה לפעול"
            } else status.text = "יש לאשר את חיבור ה‑VPN להפעלת הסינון"
            adminRequestCode -> status.text = if (resultCode == RESULT_OK) "אישור ניהול המכשיר התקבל" else "אישור ניהול המכשיר לא התקבל"
            419 -> status.text = "בקשת הגדרת דף הבית הסתיימה"
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
