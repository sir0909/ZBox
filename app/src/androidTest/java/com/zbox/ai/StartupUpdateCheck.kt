package com.zbox.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.core.ReleaseUpdate
import com.zbox.ai.ui.MainViewModel
import com.zbox.ai.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow

/** Separate test APK only. No test intent/extras/fixture hook is added to the shipped application. */
class StartupUpdateCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun waitFor(test: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8000
        while (!test()) { check(SystemClock.uptimeMillis() < until) { "UI condition timed out" }; SystemClock.sleep(100) }
    }
    private fun node(text: String): AccessibilityNodeInfo? {
        fun search(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (n == null || depth > 50) return null
            if (n.text?.toString() == text) return n
            for (i in 0 until n.childCount) search(n.getChild(i), depth + 1)?.let { return it }
            return null
        }
        // Traverse virtual Compose nodes; provider-side findByText is not universally implemented.
        return search(uiAutomation.rootInActiveWindow, 0)
    }
    private fun click(text: String) {
        var n = node(text) ?: error("Missing control: $text")
        while (!n.isClickable) n = n.parent ?: error("Not clickable: $text")
        check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    override fun onStart() {
        val result = Bundle()
        var phase = "launch"
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            phase = "initial screen"
            waitFor { node("快门") != null }
            val vmField = MainActivity::class.java.getDeclaredField("viewModelRef").apply { isAccessible = true }
            val vm = vmField.get(activity) as MainViewModel
            val field = MainViewModel::class.java.getDeclaredField("_ui").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val flow = field.get(vm) as MutableStateFlow<UiState>
            fun seed() {
                runOnMainSync { flow.value = flow.value.copy(startupUpdate = ReleaseUpdate("1.0.0", "https://github.com/sir0909/ZBox/releases/download/v1.0.0/Zbox-1.0.0.apk")) }
                waitFor { node("发现新版本") != null }
            }
            phase = "cancel dialog"
            seed(); click("暂不更新")
            waitFor { flow.value.startupUpdate == null && node("发现新版本") == null }
            runOnMainSync { vm.checkStartupUpdate { true } }
            SystemClock.sleep(500)
            check(flow.value.startupUpdate == null) { "Dismissed prompt returned" }
            phase = "back dismiss"
            seed()
            sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)); sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            waitFor { flow.value.startupUpdate == null && node("发现新版本") == null }
            var opened: String? = null
            val monitor = object : ActivityMonitor() {
                override fun onStartActivity(intent: Intent): ActivityResult? {
                    if (intent.action == Intent.ACTION_VIEW) {
                        opened = intent.dataString
                        return ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                    return null
                }
            }
            addMonitor(monitor)
            phase = "browser intent"
            seed(); click("去更新")
            waitFor { opened != null && flow.value.startupUpdate == null && node("发现新版本") == null }
            removeMonitor(monitor)
            check(opened == "https://github.com/sir0909/ZBox/releases/download/v1.0.0/Zbox-1.0.0.apk") { "Unexpected browser intent: $opened" }
            check(node("发现新版本") == null)
            result.putString("stream", "PASS actual dialog visible; cancel dismisses; repeated startup is gated; back dismisses; update emits browser intent and clears dialog. Browser intent intercepted: no APK download/install.\n")
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL at $phase: ${t.javaClass.simpleName}: ${t.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
