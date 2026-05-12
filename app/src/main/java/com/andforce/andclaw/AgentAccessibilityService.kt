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
        private const val LABEL_MAX_CHARS = 60
    }

    @Volatile
    private var lastUiEventAt: Long = 0L

    @Volatile
    private var lastNodeSnapshots: Map<Int, UiNodeSnapshot> = emptyMap()

    private data class UiWindowSnapshot(
        val index: Int,
        val type: String,
        val active: Boolean,
        val focused: Boolean,
        val packageName: String?
    )

    private data class UiSnapshot(
        val screenWidth: Int,
        val screenHeight: Int,
        val windows: List<UiWindowSnapshot>,
        val nodes: List<UiNodeSnapshot>
    )

    private data class UiNodeSnapshot(
        val id: Int,
        val parentId: Int?,
        val promptDepth: Int,
        val text: String,
        val description: String,
        val label: String,
        val bounds: Rect,
        val clickBounds: Rect,
        val className: String,
        val viewId: String?,
        val packageName: String?,
        val windowType: Int,
        val windowIndex: Int,
        val nodePath: List<Int>,
        val clickNodePath: List<Int>,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val selected: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val enabled: Boolean,
        val focused: Boolean
    )

    override fun onServiceConnected() { instance = this }

    fun captureScreenHierarchy(): String {
        val snapshot = captureUiSnapshot()
        lastNodeSnapshots = snapshot.nodes.associateBy { it.id }
        return snapshot.toPromptView()
    }

    private fun captureUiSnapshot(): UiSnapshot {
        val snapshots = mutableListOf<UiNodeSnapshot>()
        val windowSnapshots = mutableListOf<UiWindowSnapshot>()
        val nextId = intArrayOf(1)
        val interactiveWindows = sortedInteractiveWindows()

        if (interactiveWindows.isNotEmpty()) {
            interactiveWindows.forEachIndexed { windowIndex, window ->
                val root = window.root ?: return@forEachIndexed
                windowSnapshots += UiWindowSnapshot(
                    index = windowIndex,
                    type = windowTypeName(window.type),
                    active = window.isActive,
                    focused = window.isFocused,
                    packageName = root.packageName?.toString()
                )
                parseNode(
                    root,
                    snapshots,
                    nextId,
                    clickableAncestorBounds = null,
                    clickableAncestorPath = null,
                    windowType = window.type,
                    windowIndex = windowIndex,
                    nodePath = emptyList(),
                    parentPromptId = null,
                    promptDepth = 0
                )
            }
        } else {
            val root = rootInActiveWindow ?: return UiSnapshot(screenWidth(), screenHeight(), emptyList(), emptyList())
            windowSnapshots += UiWindowSnapshot(
                index = 0,
                type = windowTypeName(0),
                active = true,
                focused = true,
                packageName = root.packageName?.toString()
            )
            parseNode(
                root,
                snapshots,
                nextId,
                clickableAncestorBounds = null,
                clickableAncestorPath = null,
                windowType = 0,
                windowIndex = 0,
                nodePath = emptyList(),
                parentPromptId = null,
                promptDepth = 0
            )
        }
        return UiSnapshot(screenWidth(), screenHeight(), windowSnapshots, snapshots)
    }

    private fun parseNode(
        node: AccessibilityNodeInfo?,
        snapshots: MutableList<UiNodeSnapshot>,
        nextId: IntArray,
        clickableAncestorBounds: Rect?,
        clickableAncestorPath: List<Int>?,
        windowType: Int,
        windowIndex: Int,
        nodePath: List<Int>,
        parentPromptId: Int?,
        promptDepth: Int
    ) {
        node ?: return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val label = dedupLabelPair(text, description)
        val isRelevant = shouldPromptNode(node, label, rect)
        val ownClickableBounds = if (node.isClickable && !rect.isEmpty) Rect(rect) else null
        val ownClickablePath = if (node.isClickable && !rect.isEmpty) nodePath else null
        val clickBounds = ownClickableBounds ?: clickableAncestorBounds ?: Rect(rect)
        val clickNodePath = ownClickablePath ?: clickableAncestorPath ?: nodePath

        var currentPromptId = parentPromptId
        var currentPromptDepth = promptDepth
        if (isRelevant && !rect.isEmpty) {
            val id = nextId[0]++
            val className = node.className?.toString().orEmpty()
            val snapshot = UiNodeSnapshot(
                id = id,
                parentId = parentPromptId,
                promptDepth = promptDepth,
                text = text,
                description = description,
                label = label,
                bounds = Rect(rect),
                clickBounds = Rect(clickBounds),
                className = className,
                viewId = node.viewIdResourceName,
                packageName = node.packageName?.toString(),
                windowType = windowType,
                windowIndex = windowIndex,
                nodePath = nodePath,
                clickNodePath = clickNodePath,
                clickable = node.isClickable || clickableAncestorBounds != null,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                checkable = node.isCheckable,
                checked = isNodeChecked(node),
                selected = node.isSelected,
                longClickable = node.isLongClickable,
                focusable = node.isFocusable,
                enabled = node.isEnabled,
                focused = node.isFocused
            )
            snapshots.add(snapshot)
            currentPromptId = id
            currentPromptDepth = promptDepth + 1
        }
        val nextClickableAncestor = ownClickableBounds ?: clickableAncestorBounds
        val nextClickableAncestorPath = ownClickablePath ?: clickableAncestorPath
        for (i in 0 until node.childCount) {
            parseNode(
                node.getChild(i),
                snapshots,
                nextId,
                nextClickableAncestor,
                nextClickableAncestorPath,
                windowType,
                windowIndex,
                nodePath + i,
                currentPromptId,
                currentPromptDepth
            )
        }
    }

    private fun shouldPromptNode(node: AccessibilityNodeInfo, label: String, rect: Rect): Boolean {
        if (rect.isEmpty || !node.isVisibleToUser) return false
        if (node.isEditable || node.isScrollable || node.isClickable || node.isLongClickable) return true
        if (node.isCheckable || isNodeChecked(node) || node.isSelected || node.isFocused) return true
        return label.isNotBlank()
    }

    @Suppress("DEPRECATION")
    private fun isNodeChecked(node: AccessibilityNodeInfo): Boolean = node.isChecked

    private fun UiSnapshot.toPromptView(): String {
        if (nodes.isEmpty()) return "Empty Screen"
        val sb = StringBuilder()
        sb.append("Compact UI Snapshot ${screenWidth}x${screenHeight}. Use #id as node_id for click actions.\n")

        val appNodes = nodes.filter {
            !it.isInputMethodNode() &&
                it.windowType != AccessibilityWindowInfo.TYPE_SYSTEM
        }
        val imeOpen = nodes.any { it.isInputMethodNode() }

        val visibleWindowIndices = appNodes.map { it.windowIndex }.toSet()
        windows.forEach { window ->
            if (window.index in visibleWindowIndices) {
                sb.append(
                    "W${window.index} ${window.type}" +
                        if (window.active) " active" else "" +
                        if (window.focused) " focused" else "" +
                        window.packageName?.let { " pkg=${escapeUiValue(it)}" }.orEmpty() +
                        "\n"
                )
            }
        }

        if (imeOpen) sb.append("[Keyboard open]\n")

        // Collapse text-only children whose clickBounds equal their parent's:
        // these are pure labels of a clickable container, redundant as
        // standalone node lines. Merge their labels into the parent and drop
        // the child entirely.
        val byId = appNodes.associateBy { it.id }
        val mergedLabels = mutableMapOf<Int, String>()
        val dropped = HashSet<Int>()
        appNodes.forEach { node ->
            val parent = node.parentId?.let { byId[it] } ?: return@forEach
            if (parent.id in dropped) return@forEach
            val isPureLabel = !node.editable && !node.scrollable && !node.checkable &&
                !node.checked && !node.selected && !node.focused &&
                node.label.isNotBlank() &&
                node.clickBounds == parent.clickBounds
            if (!isPureLabel) return@forEach
            val parentLabel = mergedLabels[parent.id] ?: parent.promptLabel()
            val combined = mergeLabel(parentLabel, node.label)
            mergedLabels[parent.id] = combined
            dropped += node.id
        }

        appNodes
            .filter { it.id !in dropped }
            .sortedWith(compareBy<UiNodeSnapshot> { it.windowIndex }.thenBy { it.id })
            .forEach { node ->
                sb.append(node.toPromptLine(mergedLabels[node.id]))
                sb.append('\n')
            }
        return sb.toString()
    }

    private fun mergeLabel(existing: String, addition: String): String {
        if (existing.isBlank() || existing == "(no label)") return truncateLabel(addition)
        val a = existing.trim()
        val b = addition.trim()
        if (a.equals(b, ignoreCase = true)) return truncateLabel(a)
        if (a.contains(b, ignoreCase = true)) return truncateLabel(a)
        if (b.contains(a, ignoreCase = true)) return truncateLabel(b)
        return truncateLabel("$a | $b")
    }

    private fun truncateLabel(value: String): String =
        if (value.length <= LABEL_MAX_CHARS) value else value.take(LABEL_MAX_CHARS - 3) + "..."

    private fun dedupLabelPair(text: String, description: String): String {
        val t = text.trim()
        val d = description.trim()
        return when {
            t.isBlank() && d.isBlank() -> ""
            t.isBlank() -> d
            d.isBlank() -> t
            t.equals(d, ignoreCase = true) -> t
            t.contains(d, ignoreCase = true) -> t
            d.contains(t, ignoreCase = true) -> d
            else -> "$t | $d"
        }
    }

    private fun UiNodeSnapshot.toPromptLine(labelOverride: String? = null): String {
        val indent = "  ".repeat(promptDepth.coerceIn(0, 6))
        val flags = promptFlags().takeIf { it.isNotEmpty() }?.joinToString(",", prefix = " flags=" ).orEmpty()
        val rendered = labelOverride ?: promptLabel()
        return "$indent#$id ${promptRole()} \"${escapeUiValue(rendered)}\"" +
            " tap=${clickBounds.shortRect()}" +
            flags
    }

    private fun UiNodeSnapshot.promptRole(): String = when {
        editable -> "input"
        scrollable -> "scroll"
        clickable || longClickable -> "action"
        checkable -> "toggle"
        className.contains("Button", ignoreCase = true) || className == "按钮" -> "button"
        className.contains("TextView", ignoreCase = true) -> "text"
        className.contains("Image", ignoreCase = true) -> "image"
        else -> className.substringAfterLast('.').ifBlank { "node" }
    }

    private fun UiNodeSnapshot.promptLabel(): String {
        val base = label.ifBlank {
            viewId?.substringAfter(":id/")?.takeIf { it.isNotBlank() } ?: "(no label)"
        }
        return base.replace(Regex("\\s+"), " ").trim().let(::truncateLabel)
    }

    private fun UiNodeSnapshot.promptFlags(): List<String> = buildList {
        if (clickable) add("tap")
        if (editable) add("edit")
        if (scrollable) add("scroll")
        if (selected) add("selected")
        if (checked) add("checked")
        if (focused) add("focused")
        if (!enabled) add("disabled")
        if (isInputMethodNode()) add("ime")
    }

    private fun Rect.shortRect(): String = "[${left},${top},${right},${bottom}]"

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun sortedInteractiveWindows(): List<AccessibilityWindowInfo> =
        windows.orEmpty()
            .filter { it.root != null }
            .sortedWith(
                compareBy<AccessibilityWindowInfo> { windowPromptRank(it.type) }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.isFocused }
            )

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
        val node = resolveNode(snapshot.windowIndex, snapshot.clickNodePath)
            ?: resolveNode(snapshot.windowIndex, snapshot.nodePath)
            ?: return false

        var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked && snapshot.label.isNotBlank() && !snapshot.isInputMethodNode()) {
            clicked = clickPointAndWaitForCompletion(snapshot.clickBounds.centerX(), snapshot.clickBounds.centerY())
        }
        if (clicked) waitForUiStabilization(timeoutMs = 900, quietMs = 160, minWaitMs = 260)
        return clicked
    }

    suspend fun clickBestTargetTextAndWaitForCompletion(targetText: String): Boolean {
        val target = normalizeTargetText(targetText)
        if (target.isEmpty()) return false
        val snapshot = lastNodeSnapshots.values
            .asSequence()
            .filter { it.enabled && !it.isInputMethodNode() }
            .mapNotNull { snapshot ->
                val label = normalizeTargetText(snapshot.label)
                val score = targetMatchScore(label, target)
                if (score > 0) snapshot to score else null
            }
            .sortedWith(
                compareByDescending<Pair<UiNodeSnapshot, Int>> { it.second }
                    .thenByDescending { it.first.clickable }
                    .thenBy { it.first.bounds.top }
                    .thenBy { it.first.bounds.left }
            )
            .firstOrNull()
            ?.first
            ?: return false

        val node = resolveNode(snapshot.windowIndex, snapshot.clickNodePath)
            ?: resolveNode(snapshot.windowIndex, snapshot.nodePath)
        var clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        if (!clicked) {
            clicked = clickPointAndWaitForCompletion(snapshot.clickBounds.centerX(), snapshot.clickBounds.centerY())
        }
        if (clicked) waitForUiStabilization(timeoutMs = 900, quietMs = 160, minWaitMs = 260)
        return clicked
    }

    fun isTargetTextEnabled(targetText: String): Boolean {
        val target = normalizeTargetText(targetText)
        if (target.isEmpty()) return false
        return lastNodeSnapshots.values.any { snapshot ->
            snapshot.enabled &&
                !snapshot.isInputMethodNode() &&
                targetMatchScore(normalizeTargetText(snapshot.label), target) > 0
        }
    }

    fun currentEditableText(): String? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
            node.userVisibleInputText()?.let { return it }
        }
        return rootInActiveWindow
            ?.let { findEditableNode(it) }
            ?.userVisibleInputText()
    }

    private fun AccessibilityNodeInfo.userVisibleInputText(): String? {
        val value = text?.toString()?.trim().orEmpty()
        if (value.isBlank()) return null
        val hint = hintText?.toString()?.trim().orEmpty()
        return value.takeUnless { hint.isNotBlank() && it == hint }
    }

    private fun resolveNode(windowIndex: Int, path: List<Int>): AccessibilityNodeInfo? {
        val root = sortedInteractiveWindows().getOrNull(windowIndex)?.root
            ?: (if (windowIndex == 0) rootInActiveWindow else null)
            ?: return null
        var current: AccessibilityNodeInfo = root
        for (childIndex in path) {
            current = current.getChild(childIndex) ?: return null
        }
        return current
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
        val node = findBestNodeAt(x, y) ?: return null
        return node.let {
            val rect = Rect()
            it.node.getBoundsInScreen(rect)
            val text = it.node.text?.toString().orEmpty()
            val description = it.node.contentDescription?.toString().orEmpty()
            val label = listOf(text, description).filter { it.isNotBlank() }.distinct().joinToString(" | ")
            val className = it.node.className?.toString().orEmpty().substringAfterLast('.')
            val windowType = it.node.window?.type ?: 0
            "label='${escapeUiValue(label)}', role='${escapeUiValue(className)}', package='${escapeUiValue(it.node.packageName?.toString().orEmpty())}', window_type=${windowTypeName(windowType)}, bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
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
        val target = normalizeTargetText(targetText)
        if (target.isEmpty()) return true
        val labels = mutableListOf<String>()
        val roots = sortedInteractiveWindows().mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
        roots.forEach { root -> collectLabelsAtPoint(root, x, y, labels) }
        if (labels.isEmpty()) return true
        return labels.distinct().any { label ->
            targetMatchScore(normalizeTargetText(label), target) > 0
        }
    }

    private fun normalizeTargetText(value: String): String =
        value.lowercase()
            .replace("按钮", "")
            .replace("button", "")
            .replace(Regex("[\\s，,。.:：;；'\"“”‘’()（）\\[\\]{}<>《》|]+"), "")
            .trim()

    private fun targetMatchScore(label: String, target: String): Int {
        if (label.isBlank() || target.isBlank()) return 0
        if (label == target) return 100
        if (label.contains(target) || target.contains(label)) return 80
        return 0
    }

    private data class NodeAtPoint(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val area: Int
    )

    private fun findBestNodeAt(x: Int, y: Int): NodeAtPoint? {
        val candidates = mutableListOf<NodeAtPoint>()
        val roots = sortedInteractiveWindows().mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
        roots.forEach { root -> collectNodesAtPoint(root, x, y, depth = 0, candidates) }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareByDescending<NodeAtPoint> { nodeMeaningScore(it.node) }
                .thenByDescending { it.depth }
                .thenBy { it.area }
        ).first()
    }

    private fun nodeMeaningScore(node: AccessibilityNodeInfo): Int {
        val label = nodeLabel(node)
        var score = 0
        if (label.isNotBlank()) score += 1000
        if (node.isClickable) score += 120
        if (node.isFocusable) score += 80
        if (node.isEnabled) score += 20
        if (label.contains("按钮") || label.contains("button", ignoreCase = true)) score += 40
        return score
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        return listOf(text, description).filter { it.isNotBlank() }.distinct().joinToString(" | ")
    }

    private fun collectNodesAtPoint(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        depth: Int,
        candidates: MutableList<NodeAtPoint>
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return
        candidates.add(NodeAtPoint(node, depth, rect.width() * rect.height()))
        for (i in 0 until node.childCount) {
            collectNodesAtPoint(node.getChild(i) ?: continue, x, y, depth + 1, candidates)
        }
    }

    private fun collectLabelsAtPoint(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        labels: MutableList<String>
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return
        nodeLabel(node).takeIf { it.isNotBlank() }?.let { labels.add(it) }
        for (i in 0 until node.childCount) {
            collectLabelsAtPoint(node.getChild(i) ?: continue, x, y, labels)
        }
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
        return clickPointAndWaitForCompletion(x, y)
    }

    private suspend fun clickPointAndWaitForCompletion(x: Int, y: Int): Boolean {
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
