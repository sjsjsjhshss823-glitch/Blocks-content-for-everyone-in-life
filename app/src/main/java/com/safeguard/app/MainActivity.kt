package com.safeguard.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("safeguard", Context.MODE_PRIVATE) }
    private lateinit var listContainer: LinearLayout
    private lateinit var status: TextView

    private fun domains(): MutableSet<String> =
        prefs.getStringSet("domains", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun save(items: Set<String>) {
        prefs.edit().putStringSet("domains", items.toSet()).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(TextView(this).apply {
            text = "SafeGuard"
            textSize = 30f
            setTextColor(Color.rgb(22, 42, 72))
            setTypeface(null, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Content safety · Android prototype"
            textSize = 16f
            setPadding(0, 4, 0, 20)
            setTextColor(Color.DKGRAY)
        })

        val input = EditText(this).apply {
            hint = "Domain, e.g. example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        content.addView(input)
        content.addView(Button(this).apply {
            text = "Add domain"
            setOnClickListener {
                val raw = input.text.toString().trim().lowercase()
                val normalized = raw.removePrefix("https://").removePrefix("http://")
                    .removePrefix("www.").substringBefore('/').substringBefore('?').substringBefore('#')
                if (!normalized.contains('.') || normalized.any { it.isWhitespace() }) {
                    Toast.makeText(this@MainActivity, "Enter a domain such as example.com", Toast.LENGTH_SHORT).show()
                } else {
                    val items = domains()
                    items.add(normalized)
                    save(items)
                    input.text.clear()
                    renderList()
                    status.text = "Domain saved on this phone."
                }
            }
        })

        content.addView(TextView(this).apply {
            text = "Check a URL"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 22, 0, 4)
            setTextColor(Color.rgb(22, 42, 72))
        })
        val urlInput = EditText(this).apply {
            hint = "https://example.com/page"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        content.addView(urlInput)
        content.addView(Button(this).apply {
            text = "Check URL"
            setOnClickListener {
                val host = try {
                    android.net.Uri.parse(urlInput.text.toString().trim()).host?.lowercase()?.removePrefix("www.")
                } catch (_: Exception) { null }
                if (host == null) {
                    status.text = "Enter a complete URL, including https://"
                } else {
                    val match = domains().any { host == it || host.endsWith(".$it") }
                    status.text = if (match) "MATCH: this domain is on your list." else "No match in your local list."
                }
            }
        })

        content.addView(TextView(this).apply {
            text = "Your domain list"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 22, 0, 8)
            setTextColor(Color.rgb(22, 42, 72))
        })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)
        status = TextView(this).apply {
            text = "Your list is stored locally on this device."
            textSize = 15f
            setPadding(0, 18, 0, 8)
            setTextColor(Color.rgb(30, 80, 50))
        }
        content.addView(status)
        content.addView(TextView(this).apply {
            text = "Important: this version manages a list and checks URLs entered here. It does not yet block traffic in other apps or across the whole phone."
            textSize = 13f
            setPadding(0, 12, 0, 12)
            setTextColor(Color.DKGRAY)
        })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val items = domains().sorted()
        if (items.isEmpty()) {
            listContainer.addView(TextView(this).apply { text = "No domains added yet."; textSize = 15f })
        }
        items.forEach { domain ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            row.addView(TextView(this).apply {
                text = domain
                textSize = 16f
                setTextColor(Color.BLACK)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    val updated = domains()
                    updated.remove(domain)
                    save(updated)
                    renderList()
                    status.text = "Domain removed."
                }
            })
            listContainer.addView(row)
        }
    }
}
