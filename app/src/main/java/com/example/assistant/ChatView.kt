package com.example.assistant

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ChatView(private val ctx: Context, private val scroll: ScrollView) {
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private fun bubble(text: String, bg: Int, fg: Int, side: Int, caption: String? = null) {
        val tv = TextView(ctx).apply {
            this.text = text; textSize = 16f; setTextColor(fg); setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(18).toFloat() }
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.78).toInt()
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv)
        if (caption != null) col.addView(TextView(ctx).apply { this.text = caption; textSize = 10f; setTextColor(0xFF6B7788.toInt()); setPadding(dp(6), dp(2), dp(6), 0) })
        box.addView(col, LinearLayout.LayoutParams(-2, -2).apply { this.gravity = side; setMargins(0, dp(4), 0, dp(4)) })
    }

    fun setTextColor(c: Int) {}

    fun append(raw: String) {
        val s = raw.trim(); if (s.isEmpty()) return
        fun strip(p: String) = s.removePrefix(p).removePrefix("\uFE0F").trim()
        when {
            s.startsWith("\uD83E\uDD16") -> bubble(strip("\uD83E\uDD16"), 0xFF1B2230.toInt(), 0xFFE8EDF2.toInt(), Gravity.START)
            s.startsWith("\uD83E\uDDD1") || s.startsWith("\u2328") -> {
                val body = if (s.startsWith("\u2328")) strip("\u2328") else strip("\uD83E\uDDD1")
                val m = Regex("^\\[(\\w+)\\]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(body)
                if (m != null) bubble(m.groupValues[2], 0xFF5B3DF5.toInt(), 0xFFFFFFFF.toInt(), Gravity.END, "via " + m.groupValues[1])
                else bubble(body, 0xFF5B3DF5.toInt(), 0xFFFFFFFF.toInt(), Gravity.END)
            }
            else -> box.addView(TextView(ctx).apply {
                text = s; textSize = 12f; setTextColor(0xFF7F8CA0.toInt()); gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(4))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
