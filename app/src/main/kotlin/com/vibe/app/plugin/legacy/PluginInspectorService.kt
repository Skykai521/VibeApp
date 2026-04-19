package com.vibe.app.plugin.legacy

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import com.vibe.app.plugin.IPluginInspector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Static registry for passing Activity references between
 * PluginContainerActivity and PluginInspectorService in the same process.
 */
object ActivityHolder {
    private val activities = arrayOfNulls<Activity>(PluginManager.MAX_SLOTS)

    fun set(slotIndex: Int, activity: Activity) {
        activities[slotIndex] = activity
    }

    fun get(slotIndex: Int): Activity? = activities.getOrNull(slotIndex)

    fun clear(slotIndex: Int) {
        if (slotIndex in activities.indices) {
            activities[slotIndex] = null
        }
    }
}

/**
 * Bound Service running in each plugin process.
 * AIDL surface is three methods: dumpViewTree, executeAction, captureScreenshot.
 * All behavior is driven by JSON options/action payloads so the AIDL can stay stable
 * as new inspection/action capabilities are added.
 */
open class PluginInspectorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Subclasses override to return their slot index. */
    protected open val slotIndex: Int = -1

    private val binder = object : IPluginInspector.Stub() {

        override fun dumpViewTree(optionsJson: String?): String {
            return try {
                val options = DumpOptions.parse(optionsJson)
                runOnMainThreadBlocking { doDump(options) }
            } catch (e: Exception) {
                jsonError(e.message ?: "dump failed")
            }
        }

        override fun executeAction(actionJson: String?): String {
            return try {
                val action = JSONObject(actionJson ?: "{}")
                doExecuteAction(action)
            } catch (e: Exception) {
                jsonError("invalid action: ${e.message}")
            }
        }

        override fun captureScreenshot(optionsJson: String?): String {
            return jsonError("screenshot not implemented yet")
        }
    }

    override fun onCreate() {
        super.onCreate()
        exemptHiddenApiOnce()
    }

    override fun onBind(intent: Intent): IBinder = binder

    // ── Dump ─────────────────────────────────────────────────────────

    private data class DumpOptions(
        val scope: Scope,
        val rootSelector: JSONObject?,
        val maxDepth: Int,
        val includeWindows: Boolean,
    ) {
        enum class Scope { ALL, VISIBLE, INTERACTIVE, TEXT }

        companion object {
            fun parse(json: String?): DumpOptions {
                if (json.isNullOrBlank()) return DumpOptions(Scope.VISIBLE, null, 15, true)
                val obj = JSONObject(json)
                val scope = when (obj.optString("scope", "visible").lowercase()) {
                    "all" -> Scope.ALL
                    "interactive" -> Scope.INTERACTIVE
                    "text" -> Scope.TEXT
                    else -> Scope.VISIBLE
                }
                return DumpOptions(
                    scope = scope,
                    rootSelector = obj.optJSONObject("root"),
                    maxDepth = obj.optInt("max_depth", 15),
                    includeWindows = obj.optBoolean("include_windows", true),
                )
            }
        }
    }

    private fun doDump(options: DumpOptions): String {
        val activity = ActivityHolder.get(slotIndex)
            ?: return jsonError("no activity")
        val activityDecor = activity.window?.decorView
            ?: return jsonError("no decor view")

        val rootViews = if (options.includeWindows) {
            collectAllRootViews(activityDecor)
        } else {
            listOf(activityDecor)
        }

        val windowsArray = JSONArray()
        rootViews.forEachIndexed { index, root ->
            val treeRoot = if (options.rootSelector != null) {
                findViewInSubtree(root, options.rootSelector) ?: return@forEachIndexed
            } else {
                root
            }
            val tree = dumpView(treeRoot, 0, options) ?: return@forEachIndexed
            val windowObj = JSONObject().apply {
                put("type", classifyWindow(root, activityDecor))
                put("z_order", index)
                put("tree", tree)
            }
            windowsArray.put(windowObj)
        }

        val metrics = activity.resources.displayMetrics
        return JSONObject().apply {
            put("activity", activity.javaClass.simpleName)
            put("screen_size", JSONObject().apply {
                put("width", metrics.widthPixels)
                put("height", metrics.heightPixels)
            })
            put("windows", windowsArray)
        }.toString()
    }

    /**
     * Collect every root View in the current process (Activity main window,
     * dialogs, popup menus, dropdowns, toasts, …) across Android 10 – 16.
     *
     * The `getRootViews()` public method was removed / changed to take an IBinder
     * token in Android 11+, and the token-filtered variant does NOT return dialog
     * or popup windows — we have to reach into `WindowManagerGlobal`'s private
     * collections. Both strategies require the hidden-api exemption granted in
     * [exemptHiddenApiOnce].
     *
     * Strategy A (primary): read `WindowManagerGlobal.mViews` — the canonical
     * `ArrayList<View>` of all attached root views. Stable from Android 4 through
     * current AOSP.
     *
     * Strategy B (defensive fallback): read `WindowManagerGlobal.mRoots` and call
     * `ViewRootImpl.getView()` on each entry. `getView()` is a public method, so it
     * is not subject to hidden-api restrictions even if the class `ViewRootImpl`
     * itself is hidden.
     */
    private fun collectAllRootViews(fallback: View): List<View> {
        val clazz: Class<*>
        val instance: Any
        try {
            clazz = Class.forName("android.view.WindowManagerGlobal")
            instance = clazz.getMethod("getInstance").invoke(null)
                ?: return listOf(fallback)
        } catch (t: Throwable) {
            Log.w(TAG, "WindowManagerGlobal.getInstance() unavailable", t)
            return listOf(fallback)
        }

        // Strategy A — mViews field.
        try {
            val field = clazz.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val raw = field.get(instance) as? List<View>
            if (!raw.isNullOrEmpty()) {
                val snapshot = ArrayList(raw)
                logFirstStrategySuccess("mViews", snapshot.size)
                return snapshot
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WMG.mViews strategy failed", t)
        }

        // Strategy B — mRoots field + ViewRootImpl.getView().
        try {
            val field = clazz.getDeclaredField("mRoots").apply { isAccessible = true }
            val roots = field.get(instance) as? List<*>
            if (!roots.isNullOrEmpty()) {
                val views = ArrayList<View>(roots.size)
                for (root in roots) {
                    if (root == null) continue
                    try {
                        val v = root.javaClass.getMethod("getView").invoke(root) as? View
                        if (v != null) views.add(v)
                    } catch (_: Throwable) {
                        // Individual root failure shouldn't break the whole pass.
                    }
                }
                if (views.isNotEmpty()) {
                    logFirstStrategySuccess("mRoots.getView", views.size)
                    return views
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WMG.mRoots fallback failed", t)
        }

        Log.w(TAG, "collectAllRootViews: no strategy worked; only Activity decorView visible (API ${Build.VERSION.SDK_INT})")
        return listOf(fallback)
    }

    private fun logFirstStrategySuccess(name: String, size: Int) {
        if (strategyLogged) return
        strategyLogged = true
        Log.i(TAG, "collectAllRootViews via $name ($size views, API ${Build.VERSION.SDK_INT})")
    }

    private fun classifyWindow(rootView: View, activityDecor: View): String {
        if (rootView === activityDecor) return "activity"
        val params = rootView.layoutParams as? WindowManager.LayoutParams ?: return "popup"
        return when (params.type) {
            WindowManager.LayoutParams.TYPE_TOAST -> "toast"
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.TYPE_BASE_APPLICATION -> "activity_secondary"
            else -> "popup"
        }
    }

    private fun dumpView(view: View, depth: Int, options: DumpOptions): JSONObject? {
        if (view.visibility == View.GONE) return null
        if (options.scope == DumpOptions.Scope.VISIBLE && view.visibility != View.VISIBLE) return null

        val childrenArray = JSONArray()
        var anyChildIncluded = false
        if (view is ViewGroup && depth < options.maxDepth) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val childJson = dumpView(child, depth + 1, options)
                if (childJson != null) {
                    childrenArray.put(childJson)
                    anyChildIncluded = true
                }
            }
        }

        val selfMatches = matchesScope(view, options.scope)
        if (!selfMatches && !anyChildIncluded) return null

        val node = JSONObject()
        node.put("class", view.javaClass.simpleName)
        if (view.id != View.NO_ID) {
            try {
                node.put("id", view.resources.getResourceEntryName(view.id))
            } catch (_: Exception) { /* dynamic or unnamed ID */ }
        }
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            node.put("text", truncate(text))
            if (view is EditText) {
                node.put("hint", view.hint?.toString().orEmpty())
            }
        }
        view.contentDescription?.let { desc ->
            node.put("contentDescription", truncate(desc.toString()))
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        node.put("bounds", JSONObject().apply {
            put("left", loc[0])
            put("top", loc[1])
            put("right", loc[0] + view.width)
            put("bottom", loc[1] + view.height)
        })
        node.put("visibility", when (view.visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            else -> "gone"
        })
        node.put("clickable", view.isClickable)
        node.put("enabled", view.isEnabled)
        node.put("focusable", view.isFocusable)
        if (childrenArray.length() > 0) node.put("children", childrenArray)
        return node
    }

    private fun matchesScope(view: View, scope: DumpOptions.Scope): Boolean {
        return when (scope) {
            DumpOptions.Scope.ALL -> true
            DumpOptions.Scope.VISIBLE -> view.visibility == View.VISIBLE
            DumpOptions.Scope.INTERACTIVE ->
                view is EditText || view.isClickable || view.isLongClickable
            DumpOptions.Scope.TEXT -> {
                val hasText = (view as? TextView)?.text?.isNotEmpty() == true
                val hasDesc = !view.contentDescription.isNullOrEmpty()
                hasText || hasDesc
            }
        }
    }

    private fun truncate(s: String): String =
        if (s.length > MAX_TEXT_LENGTH) s.take(MAX_TEXT_LENGTH) + "\u2026" else s

    // ── Actions ──────────────────────────────────────────────────────

    private fun doExecuteAction(action: JSONObject): String {
        return when (val name = action.optString("action")) {
            "click" -> doClick(action)
            "long_click" -> doLongClick(action)
            "double_click" -> doDoubleClick(action)
            "input" -> doInput(action)
            "clear_text" -> doClearText(action)
            "scroll" -> doScroll(action)
            "scroll_to" -> doScrollTo(action)
            "swipe" -> doSwipe(action)
            "key" -> doKey(action)
            "wait" -> doWait(action)
            "wait_for" -> doWaitFor(action)
            else -> jsonError("unknown action: $name")
        }
    }

    private fun doClick(action: JSONObject): String {
        val selectorJson = action.optJSONObject("selector")?.toString()
            ?: return jsonError("selector required")
        val result = runOnMainThreadBlocking {
            val view = findViewBySelector(selectorJson)
                ?: return@runOnMainThreadBlocking jsonError("view not found")
            view.performClick()
            jsonOk()
        }
        waitForIdle()
        return result
    }

    private fun doLongClick(action: JSONObject): String {
        val selectorJson = action.optJSONObject("selector")?.toString()
            ?: return jsonError("selector required")
        val result = runOnMainThreadBlocking {
            val view = findViewBySelector(selectorJson)
                ?: return@runOnMainThreadBlocking jsonError("view not found")
            if (!view.performLongClick()) {
                // Fall back to motion events if view has no long-click handler registered
                performTap(view, longPress = true)
            }
            jsonOk()
        }
        waitForIdle()
        return result
    }

    private fun doDoubleClick(action: JSONObject): String {
        val selectorJson = action.optJSONObject("selector")?.toString()
            ?: return jsonError("selector required")
        val result = runOnMainThreadBlocking {
            val view = findViewBySelector(selectorJson)
                ?: return@runOnMainThreadBlocking jsonError("view not found")
            performDoubleTap(view)
            jsonOk()
        }
        waitForIdle()
        return result
    }

    private fun doInput(action: JSONObject): String {
        val selectorJson = action.optJSONObject("selector")?.toString()
            ?: return jsonError("selector required")
        val value = action.optString("value", "")
        val clearFirst = action.optBoolean("clear_first", true)
        val submit = action.optBoolean("submit", false)
        val result = runOnMainThreadBlocking {
            val view = findViewBySelector(selectorJson) as? EditText
                ?: return@runOnMainThreadBlocking jsonError("EditText not found")
            if (clearFirst) {
                view.setText(value)
            } else {
                view.append(value)
            }
            if (submit) {
                val imeAction = view.imeOptions and EditorInfo.IME_MASK_ACTION
                view.onEditorAction(if (imeAction != 0) imeAction else EditorInfo.IME_ACTION_DONE)
            }
            jsonOk()
        }
        waitForIdle()
        return result
    }

    private fun doClearText(action: JSONObject): String {
        val selectorJson = action.optJSONObject("selector")?.toString()
            ?: return jsonError("selector required")
        val result = runOnMainThreadBlocking {
            val view = findViewBySelector(selectorJson) as? EditText
                ?: return@runOnMainThreadBlocking jsonError("EditText not found")
            view.setText("")
            jsonOk()
        }
        waitForIdle()
        return result
    }

    private fun doScroll(action: JSONObject): String {
        val selectorJson = action.optJSONObject("selector")?.toString()
            ?: return jsonError("selector required")
        val direction = action.optString("direction", "down")
        val amount = action.optInt("amount", 500)
        val result = runOnMainThreadBlocking {
            val view = findViewBySelector(selectorJson)
                ?: return@runOnMainThreadBlocking jsonError("view not found")
            when (direction) {
                "up" -> view.scrollBy(0, -amount)
                "down" -> view.scrollBy(0, amount)
                "left" -> view.scrollBy(-amount, 0)
                "right" -> view.scrollBy(amount, 0)
                else -> return@runOnMainThreadBlocking jsonError("invalid direction: $direction")
            }
            jsonOk()
        }
        waitForIdle()
        return result
    }

    private fun doScrollTo(action: JSONObject): String {
        val selectorJson = action.optJSONObject("selector")?.toString()
            ?: return jsonError("selector required")
        val result = runOnMainThreadBlocking {
            val view = findViewBySelector(selectorJson)
                ?: return@runOnMainThreadBlocking jsonError("view not found")
            val rect = Rect(0, 0, view.width, view.height)
            view.requestRectangleOnScreen(rect, false)
            jsonOk()
        }
        waitForIdle()
        return result
    }

    private fun doSwipe(action: JSONObject): String {
        val duration = action.optLong("duration_ms", 300L).coerceAtLeast(1L)
        val result = runOnMainThreadBlocking {
            val activity = ActivityHolder.get(slotIndex)
                ?: return@runOnMainThreadBlocking jsonError("no activity")
            val rootView = activity.window?.decorView
                ?: return@runOnMainThreadBlocking jsonError("no decor view")

            val coords = resolveSwipeCoords(action, rootView)
                ?: return@runOnMainThreadBlocking jsonError("invalid swipe coords: need from/to or from_selector/to_selector")

            performSwipe(rootView, coords[0], coords[1], coords[2], coords[3], duration)
            jsonOk()
        }
        waitForIdle()
        return result
    }

    private fun doKey(action: JSONObject): String {
        val keyName = action.optString("key").lowercase()
        val keyCode = when (keyName) {
            "back" -> KeyEvent.KEYCODE_BACK
            "enter" -> KeyEvent.KEYCODE_ENTER
            "tab" -> KeyEvent.KEYCODE_TAB
            "search" -> KeyEvent.KEYCODE_SEARCH
            "delete" -> KeyEvent.KEYCODE_DEL
            else -> return jsonError("unknown key: $keyName")
        }
        val result = runOnMainThreadBlocking {
            val activity = ActivityHolder.get(slotIndex)
                ?: return@runOnMainThreadBlocking jsonError("no activity")
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            activity.dispatchKeyEvent(down)
            activity.dispatchKeyEvent(up)
            jsonOk()
        }
        waitForIdle()
        return result
    }

    private fun doWait(action: JSONObject): String {
        val ms = action.optLong("duration_ms", 500L).coerceIn(0L, 10000L)
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return jsonOk()
    }

    private fun doWaitFor(action: JSONObject): String {
        val selectorJson = action.optJSONObject("selector")?.toString()
            ?: return jsonError("selector required")
        val condition = action.optString("condition", "appears")
        val timeoutMs = action.optLong("timeout_ms", 5000L).coerceIn(100L, 30000L)
        val pollInterval = 200L
        val deadline = SystemClock.uptimeMillis() + timeoutMs

        while (SystemClock.uptimeMillis() < deadline) {
            val matched = runOnMainThreadBlocking {
                val view = findViewBySelector(selectorJson)
                when (condition) {
                    "appears" -> view != null && isViewVisibleOnScreen(view)
                    "disappears" -> view == null || !isViewVisibleOnScreen(view)
                    "clickable" -> view != null && view.isClickable && view.isEnabled && isViewVisibleOnScreen(view)
                    else -> false
                }
            }
            if (matched) return jsonOk()
            try {
                Thread.sleep(pollInterval)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return jsonError("wait_for timeout: condition=$condition")
    }

    private fun isViewVisibleOnScreen(view: View): Boolean {
        if (view.visibility != View.VISIBLE || !view.isAttachedToWindow) return false
        return view.isShown && view.width > 0 && view.height > 0
    }

    // ── Gesture helpers ──────────────────────────────────────────────

    private fun viewCenterInRoot(view: View, rootView: View): Pair<Float, Float> {
        val viewLoc = IntArray(2)
        val rootLoc = IntArray(2)
        view.getLocationOnScreen(viewLoc)
        rootView.getLocationOnScreen(rootLoc)
        val cx = (viewLoc[0] - rootLoc[0] + view.width / 2f)
        val cy = (viewLoc[1] - rootLoc[1] + view.height / 2f)
        return cx to cy
    }

    private fun performTap(view: View, longPress: Boolean) {
        val rootView = view.rootView ?: return
        val (cx, cy) = viewCenterInRoot(view, rootView)
        val downTime = SystemClock.uptimeMillis()
        val upDelay = if (longPress) 700L else 50L

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
        rootView.dispatchTouchEvent(down)
        down.recycle()

        val up = MotionEvent.obtain(downTime, downTime + upDelay, MotionEvent.ACTION_UP, cx, cy, 0)
        rootView.dispatchTouchEvent(up)
        up.recycle()
    }

    private fun performDoubleTap(view: View) {
        val rootView = view.rootView ?: return
        val (cx, cy) = viewCenterInRoot(view, rootView)
        val t0 = SystemClock.uptimeMillis()

        // First tap
        MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, cx, cy, 0).also {
            rootView.dispatchTouchEvent(it); it.recycle()
        }
        MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_UP, cx, cy, 0).also {
            rootView.dispatchTouchEvent(it); it.recycle()
        }

        // Second tap after a short gap (well under double-tap timeout ~300ms)
        val t1 = t0 + 120
        MotionEvent.obtain(t1, t1, MotionEvent.ACTION_DOWN, cx, cy, 0).also {
            rootView.dispatchTouchEvent(it); it.recycle()
        }
        MotionEvent.obtain(t1, t1 + 40, MotionEvent.ACTION_UP, cx, cy, 0).also {
            rootView.dispatchTouchEvent(it); it.recycle()
        }
    }

    private fun resolveSwipeCoords(action: JSONObject, rootView: View): FloatArray? {
        val fromObj = action.optJSONObject("from")
        val toObj = action.optJSONObject("to")
        if (fromObj != null && toObj != null) {
            val rootLoc = IntArray(2)
            rootView.getLocationOnScreen(rootLoc)
            return floatArrayOf(
                (fromObj.optInt("x", 0) - rootLoc[0]).toFloat(),
                (fromObj.optInt("y", 0) - rootLoc[1]).toFloat(),
                (toObj.optInt("x", 0) - rootLoc[0]).toFloat(),
                (toObj.optInt("y", 0) - rootLoc[1]).toFloat(),
            )
        }
        val fromSel = action.optJSONObject("from_selector")
        val toSel = action.optJSONObject("to_selector")
        if (fromSel != null && toSel != null) {
            val fromView = findViewBySelector(fromSel.toString()) ?: return null
            val toView = findViewBySelector(toSel.toString()) ?: return null
            val (fx, fy) = viewCenterInRoot(fromView, rootView)
            val (tx, ty) = viewCenterInRoot(toView, rootView)
            return floatArrayOf(fx, fy, tx, ty)
        }
        return null
    }

    private fun performSwipe(rootView: View, fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        val downTime = SystemClock.uptimeMillis()
        val steps = 16

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY, 0)
        rootView.dispatchTouchEvent(down)
        down.recycle()

        for (i in 1..steps) {
            val t = downTime + (durationMs * i / steps)
            val x = fromX + (toX - fromX) * i / steps
            val y = fromY + (toY - fromY) * i / steps
            val move = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE, x, y, 0)
            rootView.dispatchTouchEvent(move)
            move.recycle()
        }

        val up = MotionEvent.obtain(downTime, downTime + durationMs, MotionEvent.ACTION_UP, toX, toY, 0)
        rootView.dispatchTouchEvent(up)
        up.recycle()
    }

    // ── View finding ─────────────────────────────────────────────────

    private fun findViewBySelector(selectorJson: String): View? {
        val activity = ActivityHolder.get(slotIndex) ?: return null
        val activityDecor = activity.window?.decorView ?: return null
        val roots = collectAllRootViews(activityDecor)
        val sel = JSONObject(selectorJson)

        val underObj = sel.optJSONObject("under")
        val searchRoots: List<View> = if (underObj != null) {
            val underView = roots.firstNotNullOfOrNull { findViewInSubtree(it, underObj) }
                ?: return null
            listOf(underView)
        } else {
            roots
        }

        val type = sel.optString("type").takeIf { it.isNotEmpty() } ?: return null
        val value = sel.optString("value")
        val targetIndex = sel.optInt("index", 0)
        val clickableOnly = sel.optBoolean("clickable_only", false)

        val counter = intArrayOf(0)
        for (root in searchRoots) {
            val found = findViewRecursive(root, type, value, targetIndex, counter, clickableOnly)
            if (found != null) return found
        }
        return null
    }

    private fun findViewInSubtree(root: View, selObj: JSONObject): View? {
        val type = selObj.optString("type").takeIf { it.isNotEmpty() } ?: return null
        val value = selObj.optString("value")
        val targetIndex = selObj.optInt("index", 0)
        val clickableOnly = selObj.optBoolean("clickable_only", false)
        return findViewRecursive(root, type, value, targetIndex, intArrayOf(0), clickableOnly)
    }

    private fun findViewRecursive(
        view: View,
        type: String,
        value: String,
        targetIndex: Int,
        counter: IntArray,
        clickableOnly: Boolean,
    ): View? {
        if (matchesSelector(view, type, value) && (!clickableOnly || view.isClickable)) {
            if (counter[0] == targetIndex) return view
            counter[0]++
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findViewRecursive(view.getChildAt(i), type, value, targetIndex, counter, clickableOnly)
                if (found != null) return found
            }
        }
        return null
    }

    private fun matchesSelector(view: View, type: String, value: String): Boolean {
        return when (type) {
            "id" -> {
                if (view.id == View.NO_ID) false
                else try {
                    view.resources.getResourceEntryName(view.id) == value
                } catch (_: Exception) { false }
            }
            "text" -> (view as? TextView)?.text?.toString() == value
            "text_contains" -> (view as? TextView)?.text?.toString()?.contains(value) == true
            "text_regex" -> {
                val text = (view as? TextView)?.text?.toString() ?: return false
                try { Regex(value).containsMatchIn(text) } catch (_: Exception) { false }
            }
            "desc" -> view.contentDescription?.toString() == value
            "desc_contains" -> view.contentDescription?.toString()?.contains(value) == true
            "class" -> view.javaClass.simpleName == value
            else -> false
        }
    }

    // ── Thread helpers ───────────────────────────────────────────────

    private fun <T> runOnMainThreadBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        mainHandler.post {
            try {
                result = block()
            } catch (e: Throwable) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw TimeoutException("Main thread operation timed out after ${TIMEOUT_SECONDS}s")
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * Wait for the UI to settle after an action: two Choreographer frames + a small buffer.
     * Must be called off the main thread (from the binder/IPC thread).
     */
    private fun waitForIdle(timeoutMs: Long = 1500) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val choreographer = Choreographer.getInstance()
                choreographer.postFrameCallback {
                    choreographer.postFrameCallback {
                        mainHandler.postDelayed({ latch.countDown() }, IDLE_BUFFER_MS)
                    }
                }
            } catch (_: Throwable) {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    // ── Hidden API bypass ────────────────────────────────────────────

    private fun exemptHiddenApiOnce() {
        if (hiddenApiExempted) return
        hiddenApiExempted = true
        Log.i(TAG, "init: Build.VERSION.SDK_INT=${Build.VERSION.SDK_INT}, pid=${android.os.Process.myPid()}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.i(TAG, "HiddenApiBypass skipped (pre-Pie)")
            return
        }
        try {
            // Empty prefix "L" matches every Java reference type, opening reflection
            // to WindowManagerGlobal / ViewRootImpl / related hidden APIs used by
            // the multi-window view-tree dump.
            val ok = HiddenApiBypass.addHiddenApiExemptions("L")
            Log.i(TAG, "HiddenApiBypass.addHiddenApiExemptions returned $ok")
        } catch (t: Throwable) {
            Log.w(TAG, "HiddenApiBypass threw during init — will rely on direct invoke fallback", t)
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────

    private fun jsonOk(): String = """{"success":true}"""

    private fun jsonError(msg: String): String = JSONObject().apply {
        put("success", false)
        put("error", msg)
    }.toString()

    companion object {
        private const val TAG = "PluginInspector"
        private const val MAX_TEXT_LENGTH = 100
        private const val TIMEOUT_SECONDS = 5L
        private const val IDLE_BUFFER_MS = 100L

        @Volatile
        private var hiddenApiExempted = false

        @Volatile
        private var strategyLogged = false
    }
}

// 5 process-isolated inspector slots — each declared in manifest with matching plugin process
class PluginInspectorSlot0 : PluginInspectorService() { override val slotIndex = 0 }
class PluginInspectorSlot1 : PluginInspectorService() { override val slotIndex = 1 }
class PluginInspectorSlot2 : PluginInspectorService() { override val slotIndex = 2 }
class PluginInspectorSlot3 : PluginInspectorService() { override val slotIndex = 3 }
class PluginInspectorSlot4 : PluginInspectorService() { override val slotIndex = 4 }
