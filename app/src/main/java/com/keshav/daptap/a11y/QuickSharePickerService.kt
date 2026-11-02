package com.keshav.daptap.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.keshav.daptap.DapTapApp
import com.keshav.daptap.tap.AutoPick

/**
 * Optional helper: right after a tap opened Quick Share, it taps the iPhone in Quick Share's device
 * list for you, but only when exactly one nearby device is an iPhone, so it can never pick the wrong one.
 * It only reacts to the Quick Share package, and only for 25 s after a tap (see [AutoPick]).
 */
class QuickSharePickerService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AutoPick.armed) return
        val pkg = event?.packageName?.toString() ?: return
        // Quick Share's device list (Samsung's app, Google's inside Play services, or a share-sheet host).
        if (!pkg.contains("share", ignoreCase = true) && pkg != "com.google.android.gms") return
        val root = rootInActiveWindow ?: return
        val candidates = LinkedHashSet<AccessibilityNodeInfo>()
        collect(root, candidates, 0)
        if (candidates.size == 1) {
            val target = candidates.first()
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AutoPick.disarm()
                (application as? DapTapApp)?.engine?.log("auto-picked ${target.text ?: target.contentDescription ?: "iPhone"} in Quick Share")
            }
        }
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableSet<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 40) return
        val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
        if (label.contains("iphone", ignoreCase = true)) clickableAncestor(node)?.let { out += it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.isEnabled) return n
            n = n.parent
            hops++
        }
        return null
    }

    override fun onInterrupt() {}
}
