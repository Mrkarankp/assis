package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SendAccessibility : AccessibilityService() {
    companion object {
        @Volatile var armedUntil = 0L
        fun enabled(ctx: Context): Boolean =
            (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(ctx.packageName + "/")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val root = rootInActiveWindow ?: return
        val btn = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send").firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/send").firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Send").firstOrNull { it.isClickable && it.contentDescription?.toString()?.equals("Send", true) == true }
        if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) armedUntil = 0L
    }

    override fun onInterrupt() {}
}
