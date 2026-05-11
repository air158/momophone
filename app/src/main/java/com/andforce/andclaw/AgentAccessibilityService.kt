package com.andforce.andclaw

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

@SuppressLint("AccessibilityPolicy")
class AgentAccessibilityService : AccessibilityService() {
    companion object {
        var instance: AgentAccessibilityService? = null
        private const val TAG = "AiAccessibility"
    }

    @Volatile
    private var lastUiEventAt: Long = 0L

    @Volatile
    private var lastNodeSnapshots: Map<Int, UiNodeSnapshot> = emptyMap()

    private data class UiNodeSnapshot(
        val id: Int,
        val label: String,
        val bounds: Rect,
        val clickBounds: Rect,
        val className: String,
        val viewId: String?,
        val packageName: String?,
        val windowType: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val enabled: Boolean,
        val focused: Boolean
    )

    override fun onServiceConnected() { instance = this }

    fun captureScreenHierarchy(): String {
        val sb = StringBuilder()
        val snapshots = mutableListOf<UiNodeSnapshot>()
        val nextId = intArrayOf(1)
        val interactiveWindows = windows.orEmpty()
            .filter { it.root != null }
            .sortedWith(
                compareBy<AccessibilityWindowInfo> { windowPromptRank(it.type) }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.isFocused }
            )

        if (interactiveWindows.isNotEmpty()) {
            interactiveWindows.forEach { window ->
                val root = window.root ?: return@forEach
                sb.append("Window{type:${windowTypeName(window.type)}, active:${window.isActive}, focused:${window.isFocused}}\n")
                parseNode(root, sb, snapshots, nextId, clickableAncestorBounds = null, windowType = window.type)
            }
        } else {
            val root = rootInActiveWindow ?: return "Empty Screen"
            parseNode(root, sb, snapshots, nextId, clickableAncestorBounds = null, windowType = 0)
        }
        lastNodeSnapshots = snapshots.associateBy { it.id }
        return sb.toString()
    }

    private fun parseNode(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        snapshots: MutableList<UiNodeSnapshot>,
        nextId: IntArray,
        clickableAncestorBounds: Rect?,
        windowType: Int
    ) {
        node ?: return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val label = listOf(text, description).filter { it.isNotBlank() }.distinct().joinToString(" | ")
        val isRelevant = node.isClickable || node.isEditable || label.isNotBlank()
        val ownClickableBounds = if (node.isClickable && !rect.isEmpty) Rect(rect) else null
        val clickBounds = ownClickableBounds ?: clickableAncestorBounds ?: Rect(rect)

        if (isRelevant && !rect.isEmpty) {
            val id = nextId[0]++
            val className = node.className?.toString().orEmpty()
            val snapshot = UiNodeSnapshot(
                id = id,
                label = label,
                bounds = Rect(rect),
                clickBounds = Rect(clickBounds),
                className = className,
                viewId = node.viewIdResourceName,
                packageName = node.packageName?.toString(),
                windowType = windowType,
                clickable = node.isClickable || clickableAncestorBounds != null,
                editable = node.isEditable,
                enabled = node.isEnabled,
                focused = node.isFocused
            )
            snapshots.add(snapshot)
            val centerX = clickBounds.centerX()
            val centerY = clickBounds.centerY()
            sb.append(
                "{id:$id, role:'${escapeUiValue(className.substringAfterLast('.'))}', " +
                    "label:'${escapeUiValue(label.ifBlank { "(no label)" })}', " +
                    "view_id:'${escapeUiValue(node.viewIdResourceName.orEmpty())}', " +
                    "package:'${escapeUiValue(node.packageName?.toString().orEmpty())}', " +
                    "window_type:${windowTypeName(windowType)}, " +
                    "clickable:${snapshot.clickable}, editable:${node.isEditable}, enabled:${node.isEnabled}, focused:${node.isFocused}, " +
                    "bounds:[${rect.left},${rect.top},${rect.right},${rect.bottom}], " +
                    "click_bounds:[${clickBounds.left},${clickBounds.top},${clickBounds.right},${clickBounds.bottom}], " +
                    "center:[$centerX,$centerY]}\n"
            )
        }
        val nextClickableAncestor = ownClickableBounds ?: clickableAncestorBounds
        for (i in 0 until node.childCount) {
            parseNode(node.getChild(i), sb, snapshots, nextId, nextClickableAncestor, windowType)
        }
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
        else -> type.toString()
    }

    private fun windowPromptRank(type: Int): Int = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> 0
        AccessibilityWindowInfo.TYPE_SYSTEM -> 1
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> 2
        else -> 3
    }

    private fun escapeUiValue(value: String): String =
        value.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\r", " ")

    suspend fun clickNodeAndWaitForCompletion(nodeId: Int): Boolean {
        val snapshot = lastNodeSnapshots[nodeId] ?: return false
        return clickAndWaitForCompletion(snapshot.clickBounds.centerX(), snapshot.clickBounds.centerY())
    }

    fun blockedInputMethodClickReason(nodeId: Int?, x: Int, y: Int, targetText: String?): String? {
        val snapshot = nodeId?.let { lastNodeSnapshots[it] }
        val inputMethodHit = if (snapshot != null) {
            snapshot.isInputMethodNode()
        } else {
            isPointInInputMethodWindow(x, y)
        }
        if (!inputMethodHit) return null

        val target = targetText?.trim().orEmpty()
        val label = snapshot?.label.orEmpty()
        val text = target.ifBlank { label.ifBlank { "submit" } }
        return "Click blocked: '$text' is in the input-method keyboard window. Use text_input for typing; for submitting a comment/reply/post, re-check the UI tree/screenshot and choose the app's own send/post/comment button outside the keyboard."
    }

    fun describeNodeAt(x: Int, y: Int): String? {
        val root = rootInActiveWindow ?: return null
        return findDeepestNodeAt(root, x, y)?.let { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val label = listOf(text, description).filter { it.isNotBlank() }.distinct().joinToString(" | ")
            val className = node.className?.toString().orEmpty().substringAfterLast('.')
            val windowType = node.window?.type ?: 0
            "label='${escapeUiValue(label)}', role='${escapeUiValue(className)}', package='${escapeUiValue(node.packageName?.toString().orEmpty())}', window_type=${windowTypeName(windowType)}, bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
        }
    }

    private fun isPointInInputMethodWindow(x: Int, y: Int): Boolean =
        windows.orEmpty().any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
            val rect = Rect()
            window.getBoundsInScreen(rect)
            rect.contains(x, y)
        }

    private fun UiNodeSnapshot.isInputMethodNode(): Boolean =
        windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            isInputMethodPackage(packageName) ||
            className.contains("keyboard", ignoreCase = true) ||
            className.contains("inputmethod", ignoreCase = true)

    private fun isInputMethodPackage(packageName: String?): Boolean {
        val pkg = packageName?.lowercase().orEmpty()
        return pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("latin") ||
            pkg == "com.google.android.inputmethod.latin" ||
            pkg == "com.sohu.inputmethod.sogou" ||
            pkg == "com.baidu.input" ||
            pkg == "com.iflytek.inputmethod"
    }

    fun doesNodeAtMatchTarget(x: Int, y: Int, targetText: String): Boolean {
        val root = rootInActiveWindow ?: return true
        val node = findDeepestNodeAt(root, x, y) ?: return true
        val target = targetText.trim()
        if (target.isEmpty()) return true
        val labels = collectNodeAndAncestorLabels(node)
        return labels.any { label ->
            label.contains(target, ignoreCase = true) || target.contains(label, ignoreCase = true)
        }
    }

    private fun findDeepestNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            val match = findDeepestNodeAt(child, x, y)
            if (match != null) return match
        }
        return node
    }

    private fun collectNodeAndAncestorLabels(node: AccessibilityNodeInfo): List<String> {
        val labels = mutableListOf<String>()
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            val text = current.text?.toString().orEmpty()
            val description = current.contentDescription?.toString().orEmpty()
            listOf(text, description)
                .filter { it.isNotBlank() }
                .forEach { labels.add(it) }
            current = current.parent
            depth++
        }
        return labels.distinct()
    }

    fun click(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        dispatchGesture(gesture, null, null)
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        dispatchGesture(gesture, null, null)
    }

    suspend fun clickAndWaitForCompletion(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return dispatchGestureAwait(gesture)
    }

    suspend fun swipeAndWaitForCompletion(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 300
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return dispatchGestureAwait(gesture)
    }

    suspend fun longPressAndWaitForCompletion(x: Int, y: Int, durationMs: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return dispatchGestureAwait(gesture)
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!accepted && cont.isActive) cont.resume(false)
        }

    suspend fun waitForUiStabilization(
        timeoutMs: Long = 1200,
        quietMs: Long = 180,
        minWaitMs: Long = 220,
        pollMs: Long = 40
    ) {
        val startedAt = SystemClock.uptimeMillis()
        val deadline = startedAt + timeoutMs.coerceAtLeast(0L)
        val minUntil = startedAt + minWaitMs.coerceAtLeast(0L)
        var sawEvent = false

        while (SystemClock.uptimeMillis() < deadline) {
            val eventAt = lastUiEventAt
            val now = SystemClock.uptimeMillis()
            if (eventAt >= startedAt) {
                sawEvent = true
                if (now >= minUntil && now - eventAt >= quietMs) return
            } else if (!sawEvent && now >= minUntil) {
                return
            }
            delay(pollMs)
        }
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 1000) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        dispatchGesture(gesture, null, null)
    }

    private val browserPackages = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
        "org.mozilla.firefox", "org.mozilla.fenix",
        "com.microsoft.emmx", "com.opera.browser", "com.brave.browser",
        "com.UCMobile", "com.quark.browser", "com.tencent.mtt",
        "mark.via", "org.nicoco.nicobrowser", "com.explore.web.browser",
        "com.vivaldi.browser", "com.sec.android.app.sbrowser"
    )

    fun isWebViewContext(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (isBrowserPackage(root.packageName?.toString())) return true
        return containsWebView(root)
    }

    private fun isBrowserPackage(pkg: String?): Boolean =
        pkg != null && browserPackages.contains(pkg)

    private fun containsWebView(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 15) return false
        val cls = node.className?.toString() ?: ""
        if (cls.contains("WebView", ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsWebView(child, depth + 1)) return true
        }
        return false
    }

    fun inputText(text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        }

        val root = rootInActiveWindow
        if (root != null) {
            val editableNode = findEditableNode(root)
            if (editableNode != null) {
                if (editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("input", text))
                if (editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
            }
        }

        val anyFocused = focusedNode ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (anyFocused != null) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("input", text))
            if (anyFocused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 20) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child, depth + 1)
            if (result != null) return result
        }
        return null
    }

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executor { it.run() },
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace
                    )
                    screenshot.hardwareBuffer.close()
                    callback(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "截屏失败, errorCode=$errorCode")
                    callback(null)
                }
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                lastUiEventAt = SystemClock.uptimeMillis()
            }
        }
    }
    override fun onInterrupt() {}
}
