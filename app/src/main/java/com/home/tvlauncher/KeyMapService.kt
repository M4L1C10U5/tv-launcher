package com.home.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Lets remote buttons (e.g. Prime Video) open whatever you chose in Settings → Remote buttons. */
class KeyMapService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (learning) return false
        // volume buttons while on Home: unmute the trailer widget (the button still changes volume as normal)
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            MainActivity.onVolumeKey()
        }
        val target = Prefs(this).keyMap[event.keyCode] ?: return false
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) Targets.open(this, target)
        return true // swallow the button so its old action doesn't happen
    }

    companion object {
        @Volatile var learning = false

        fun isEnabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.contains("${context.packageName}/") && list.contains("KeyMapService")
        }
    }
}
