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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val requestCode = 417
    private val prefs by lazy { getSharedPreferences("safeguard_diagnostics", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        root.addView(TextView(this).apply {
            text = "SafeGuard – הגנה בטוחה"
            textSize = 28f
            setTextColor(Color.rgb(22, 42, 72))
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "הגנה מפני אתרים למבוגרים"
            textSize = 18f
            setPadding(0, 8, 0, 24)
            setTextColor(Color.DKGRAY)
        })
        status = TextView(this).apply {
            text = "ההגנה אינה פעילה. לחצו על הכפתור ואשרו את בקשת חיבור ה‑VPN של Android."
            textSize = 16f
            setTextColor(Color.rgb(35, 65, 45))
            setPadding(0, 8, 0, 24)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "הפעלת ההגנה"
            textSize = 18f
            setOnClickListener {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent != null) startActivityForResult(intent, requestCode)
                else onActivityResult(requestCode, RESULT_OK, null)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "בדיקת מצב ודיווח על תקלה"
            textSize = 16f
            setOnClickListener { showAndShareReport() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "SafeGuard משתמשת בשירות DNS משפחתי כדי לסנן דומיינים. הסינון אינו יכול לחסום כל מילת חיפוש בכל אפליקציה או דפדפן, משום שחלק מהתעבורה מוצפנת או עוקפת DNS. להפעלה מלאה של סינון חיפוש מומלץ להפעיל גם חיפוש בטוח והגנות הורים במכשיר."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 24, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "הערה: Android אינו מאפשר לאפליקציה רגילה למנוע את הסרתה דרך הגדרות המכשיר. נעילת הסרה דורשת ניהול מכשיר או בקרת הורים ייעודית."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 16, 0, 0)
        })
        setContentView(root)
    }

    private fun showAndShareReport() {
        val keys = listOf(
            "last_start" to "הפעלה אחרונה",
            "last_heartbeat" to "פעילות אחרונה שנרשמה",
            "last_stop" to "עצירה אחרונה",
            "last_error_time" to "זמן שגיאה אחרונה"
        )
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val report = buildString {
            append("דוח אבחון SafeGuard\n")
            for ((key, label) in keys) {
                val value = prefs.getLong(key, 0L)
                append(label).append(": ")
                append(if (value > 0) dateFormat.format(Date(value)) else "לא תועד")
                append("\n")
            }
            append("פרטי שגיאה: ").append(prefs.getString("last_error", "לא תועדה שגיאה") ?: "לא תועדה שגיאה").append("\n")
            append("\nהערה: אם Android סגר את התהליך בכוח, זמן העצירה המדויק לא תמיד זמין; הפעילות האחרונה שנרשמה היא אומדן.")
        }
        status.text = report
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "דוח תקלה של SafeGuard")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(share, "שיתוף דוח התקלה"))
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            startService(Intent(this, SafeGuardVpnService::class.java))
            status.text = "ההגנה מתחילה לפעול… כאשר השירות פעיל, Android מציג סמל מפתח VPN."
        } else if (requestCode == this.requestCode) {
            status.text = "ההגנה לא הופעלה. יש לאשר את בקשת ה‑VPN כדי להמשיך."
        }
    }
}
